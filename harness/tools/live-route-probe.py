#!/usr/bin/env python3
"""Final G2 player-driven chunk route capture and semantic comparator.

Capture mode speaks the real 26.2 protocol by reusing tp-bot.py, acknowledges
chunk batches, walks a deterministic L-shaped route, and records every
ClientboundLevelChunkWithLight packet.

Comparison intentionally ignores heightmaps and block entities, which are not
part of mandatory G2 Step-9. It compares:
- blocks semantically as global block-state ids in cell order, including the
  section non-empty/fluid counts;
- each generated biome paletted-container wire payload byte-for-byte;
- the ClientboundLightUpdatePacketData payload byte-for-byte whenever both
  chunks have zero block entities (the common case).

This avoids false failures from legal block-palette ordering differences while
still enforcing the exact biome/light handoffs Step 9 claims.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import socket
import struct
import sys
import time
import uuid
from pathlib import Path

TP_PATH = Path(__file__).with_name("tp-bot.py")
SPEC = importlib.util.spec_from_file_location("kestrel_tp_bot", TP_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot import {TP_PATH}")
tp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tp)

CONFIG_CLIENT_INFO = 0x00
CONFIG_CUSTOM_PAYLOAD = 0x02  # serverbound: 0=client_info, 1=cookie_response, 2=custom_payload
CONFIG_KEEPALIVE = 0x04
PLAY_TICK_END = 0x0D
OVERWORLD_SECTIONS = 24
BLOCKS_PER_SECTION = 4096
BIOMES_PER_SECTION = 64


def read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    for _ in range(5):
        if pos >= len(buf):
            raise ValueError("truncated VarInt")
        byte = buf[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return value, pos
        shift += 7
    raise ValueError("VarInt too long")


def packed_values(buf: bytes, pos: int, bits: int, count: int) -> tuple[list[int], int]:
    if bits <= 0 or bits > 32:
        raise ValueError(f"invalid packed bits={bits}")
    per_long = 64 // bits
    if per_long <= 0:
        raise ValueError(f"invalid values-per-long bits={bits}")
    longs = (count + per_long - 1) // per_long
    end = pos + longs * 8
    if end > len(buf):
        raise ValueError("truncated packed long array")
    mask = (1 << bits) - 1
    out: list[int] = []
    for index in range(longs):
        word = struct.unpack(">Q", buf[pos + index * 8:pos + (index + 1) * 8])[0]
        for slot in range(per_long):
            if len(out) == count:
                break
            out.append((word >> (slot * bits)) & mask)
    return out, end


def decode_container(
    buf: bytes,
    pos: int,
    count: int,
    local_max_bits: int,
) -> tuple[list[int], bytes, int]:
    """Decode one 26.2 PalettedContainer and return semantic ids + raw wire."""
    start = pos
    if pos >= len(buf):
        raise ValueError("truncated paletted container")
    bits = buf[pos]
    pos += 1

    if bits == 0:
        single, pos = read_varint(buf, pos)
        return [single] * count, buf[start:pos], pos

    if bits <= local_max_bits:
        palette_size, pos = read_varint(buf, pos)
        if palette_size <= 0 or palette_size > (1 << bits):
            raise ValueError(f"bad palette size={palette_size} bits={bits}")
        palette: list[int] = []
        for _ in range(palette_size):
            entry, pos = read_varint(buf, pos)
            palette.append(entry)
        raw, pos = packed_values(buf, pos, bits, count)
        try:
            values = [palette[index] for index in raw]
        except IndexError as exc:
            raise ValueError("palette index out of range") from exc
        return values, buf[start:pos], pos

    raw, pos = packed_values(buf, pos, bits, count)
    return raw, buf[start:pos], pos


def digest_sections(section_blob: bytes) -> tuple[str, str, list[dict]]:
    pos = 0
    blocks_hash = hashlib.sha256()
    biome_wire_hash = hashlib.sha256()
    sections: list[dict] = []

    for slot in range(OVERWORLD_SECTIONS):
        if pos + 4 > len(section_blob):
            raise ValueError(f"section {slot}: missing non-empty/fluid counts")
        non_empty, fluid = struct.unpack(">hh", section_blob[pos:pos + 4])
        pos += 4

        block_ids, _, pos = decode_container(
            section_blob, pos, BLOCKS_PER_SECTION, local_max_bits=8
        )
        _, biome_wire, pos = decode_container(
            section_blob, pos, BIOMES_PER_SECTION, local_max_bits=3
        )

        blocks_hash.update(struct.pack(">hh", non_empty, fluid))
        for state_id in block_ids:
            blocks_hash.update(struct.pack(">I", state_id))

        biome_wire_hash.update(struct.pack(">I", len(biome_wire)))
        biome_wire_hash.update(biome_wire)

        sections.append(
            {
                "slot": slot,
                "non_empty": non_empty,
                "fluid": fluid,
                "biome_wire_bytes": len(biome_wire),
            }
        )

    if pos != len(section_blob):
        raise ValueError(f"section blob trailing bytes={len(section_blob) - pos}")

    return blocks_hash.hexdigest(), biome_wire_hash.hexdigest(), sections


def digest_chunk(body: bytes) -> dict:
    if len(body) < 10:
        raise ValueError("short chunk packet")

    chunk_x, chunk_z = struct.unpack(">ii", body[:8])
    pos = 8

    # 26.2 map<Heightmap.Types,long[]>; Kestrel deliberately emits an empty map.
    heightmap_count, pos = read_varint(body, pos)
    if heightmap_count > 32:
        raise ValueError(f"implausible heightmap count={heightmap_count}")
    for _ in range(heightmap_count):
        _, pos = read_varint(body, pos)
        long_count, pos = read_varint(body, pos)
        if long_count > 1024 or pos + long_count * 8 > len(body):
            raise ValueError("invalid heightmap long array")
        pos += long_count * 8

    section_len, pos = read_varint(body, pos)
    if section_len <= 0 or section_len > 2_097_152 or pos + section_len > len(body):
        raise ValueError(f"invalid section byte-array length={section_len}")
    section_blob = body[pos:pos + section_len]
    pos += section_len
    blocks_sha, biome_sha, sections = digest_sections(section_blob)

    block_entities, pos = read_varint(body, pos)

    # BlockEntityInfo NBT is intentionally out of mandatory G2 scope. Its NBT
    # has no outer byte length, so do not guess where LIGHT starts if present.
    # We still score blocks+biomes for these chunks. On the overwhelming
    # majority with zero block entities, the exact LIGHT payload is the entire
    # remaining packet suffix.
    light = body[pos:] if block_entities == 0 else None
    if block_entities == 0 and not light:
        raise ValueError("zero-block-entity chunk has no light payload")

    return {
        "x": chunk_x,
        "z": chunk_z,
        "heightmaps": heightmap_count,
        "block_entities": block_entities,
        "section_bytes": section_len,
        "blocks_sha256": blocks_sha,
        "biome_wire_sha256": biome_sha,
        "light_bytes": None if light is None else len(light),
        "light_sha256": None if light is None else hashlib.sha256(light).hexdigest(),
        "packet_bytes": len(body),
        "sections": sections,
    }


def build_client_information(view_distance: int) -> bytes:
    """Protocol-776 ClientInformation used in configuration.

    Vanilla will not advance its configuration task queue until it receives
    this packet. Keep the shape in sync with ConfigProbe.clientInformation:
    en_us, requested view distance, full chat, colours on, no skin-part flags,
    right hand, filtering off, server listing on, all particles.
    """
    if view_distance < 2 or view_distance > 16:
        raise ValueError("view distance must be in 2..16")
    return (
        tp.encode_varint(5)
        + b"en_us"
        + struct.pack(">b", view_distance)
        + tp.encode_varint(0)   # ChatVisiblity.FULL
        + b"\x01"              # chat colours
        + b"\x00"              # model customisation bitset
        + tp.encode_varint(1)   # HumanoidArm.RIGHT
        + b"\x00"              # text filtering disabled
        + b"\x01"              # allows server listing
        + tp.encode_varint(0)   # ParticleStatus.ALL
    )


def build_brand_payload() -> bytes:
    """Configuration custom payload matching a stock vanilla client brand."""
    return tp.encode_string("minecraft:brand") + tp.encode_string("vanilla")


def route_score_set(
    origin_x: float,
    origin_z: float,
    dx: float,
    dz: float,
    radius: int,
) -> tuple[list[list[int]], set[tuple[int, int]]]:
    centers: list[list[int]] = []
    scored: set[tuple[int, int]] = set()
    for x, z in (
        (origin_x, origin_z),
        (origin_x + dx, origin_z),
        (origin_x + dx, origin_z + dz),
    ):
        chunk_x = math.floor(x / 16.0)
        chunk_z = math.floor(z / 16.0)
        centers.append([chunk_x, chunk_z])
        for sx in range(chunk_x - radius, chunk_x + radius + 1):
            for sz in range(chunk_z - radius, chunk_z + radius + 1):
                scored.add((sx, sz))
    return centers, scored


def capture(args: argparse.Namespace) -> int:
    sock = socket.create_connection((args.host, args.port), timeout=15)
    wire = tp.Wire(sock)
    offline_uuid = tp.offline_uuid(args.name)

    centers, scored = route_score_set(
        args.origin_x, args.origin_z, args.dx, args.dz, args.score_radius
    )
    waypoints = []
    if args.ascend_to_route_y:
        # Vanilla first-join placement may choose the terrain surface even
        # when the shared spawn block has a different Y. Move there through
        # ordinary player movement instead of teleporting or weakening route
        # correction checks, then run the exact same horizontal route.
        waypoints.append([args.origin_x, args.route_y, args.origin_z])
    waypoints.extend([
        [args.origin_x + args.dx, args.route_y, args.origin_z],
        [args.origin_x + args.dx, args.route_y, args.origin_z + args.dz],
    ])

    chunks: dict[str, dict] = {}
    chunk_packets = 0
    batch_acks = 0
    player_loaded = False
    first_position: list[float] | None = None
    position_packets = 0
    corrections: list[list[float]] = []

    phase = "login"
    route_at: float | None = None
    next_move: float | None = None
    done_at: float | None = None
    waypoint = 0
    current: list[float] | None = None
    started = time.monotonic()
    hard_deadline = started + args.join_timeout + args.settle + args.tail + 240.0

    wire.send(0x00, tp.build_handshake(tp.PROTOCOL, args.host, args.port, 2))
    wire.send(0x00, tp.build_login_start(args.name, offline_uuid))

    try:
        while time.monotonic() < hard_deadline:
            try:
                packet_id, body = wire.read_packet(timeout=0.05)
            except TimeoutError:
                packet_id, body = None, b""

            if packet_id is not None:
                if phase == "login":
                    if packet_id == 0x03 and len(body) <= 5:
                        threshold, _ = tp.decode_varint(body, 0)
                        wire.threshold = threshold
                    elif packet_id == 0x02:
                        wire.send(tp.LOGIN_ACK, tp.build_login_ack())
                        phase = "config"
                        # Vanilla 26.2 does not advance its configuration task
                        # queue until ClientInformation arrives. Kestrel accepts
                        # the same packet and clamps the requested radius 2..16.
                        wire.send(
                            CONFIG_CLIENT_INFO,
                            build_client_information(args.view_distance),
                        )
                        wire.send(CONFIG_CUSTOM_PAYLOAD, build_brand_payload())
                    continue

                if phase == "config":
                    if packet_id == tp.CONFIG_SELECT_KNOWN_CLIENTBOUND:
                        try:
                            packs = tp.parse_known_packs(body)
                        except Exception:
                            packs = []
                        wire.send(
                            tp.CONFIG_SELECT_KNOWN_SERVERBOUND,
                            tp.encode_known_packs(packs),
                        )
                    elif packet_id == CONFIG_KEEPALIVE and len(body) == 8:
                        wire.send(CONFIG_KEEPALIVE, body)
                    elif packet_id == tp.CONFIG_FINISH and len(body) == 0:
                        wire.send(tp.CONFIG_FINISH, tp.build_config_finish())
                        phase = "play"
                    continue

                if packet_id == tp.PLAY_CHUNK_BATCH_FINISHED:
                    batch_acks += 1
                    wire.send(
                        tp.PLAY_CHUNK_BATCH_RECEIVED,
                        struct.pack(">f", args.chunks_per_tick_ack),
                    )
                elif packet_id == tp.PLAY_CHUNK and len(body) >= 8:
                    chunk_packets += 1
                    chunk_x, chunk_z = struct.unpack(">ii", body[:8])
                    key = f"{chunk_x},{chunk_z}"
                    try:
                        item = digest_chunk(body)
                        item["received_s"] = round(time.monotonic() - started, 4)
                        item["receptions"] = chunks.get(key, {}).get("receptions", 0) + 1
                    except Exception as exc:
                        item = {
                            "x": chunk_x,
                            "z": chunk_z,
                            "parse_error": str(exc),
                            "raw_sha256": hashlib.sha256(body).hexdigest(),
                        }
                    chunks[key] = item
                elif len(body) == 8 and packet_id == tp.PLAY_KEEPALIVE_IN:
                    wire.send(tp.PLAY_KEEPALIVE_OUT, body)
                elif packet_id in (tp.PLAY_DISCONNECT, tp.PLAY_DISCONNECT_ALT):
                    raise RuntimeError(f"server disconnect packet 0x{packet_id:02x}")
                elif packet_id == tp.PLAY_POSITION:
                    teleport_id, x, y, z, _, _ = tp.decode_clientbound_position(body)
                    wire.send(
                        tp.PLAY_ACCEPT_TELEPORT,
                        tp.build_accept_teleport(teleport_id),
                    )
                    position_packets += 1
                    if not player_loaded:
                        wire.send(tp.PLAY_PLAYER_LOADED)
                        player_loaded = True

                    if first_position is None:
                        first_position = [x, y, z]
                        if (
                            abs(x - args.origin_x) > args.start_tolerance
                            or abs(z - args.origin_z) > args.start_tolerance
                        ):
                            raise RuntimeError(
                                f"unexpected initial x/z {(x, z)}; "
                                f"expected near {(args.origin_x, args.origin_z)}"
                            )
                        if (
                            not args.ascend_to_route_y
                            and abs(y - args.route_y) > args.y_tolerance
                        ):
                            raise RuntimeError(
                                f"unexpected initial y={y}; expected near {args.route_y}"
                            )
                        current = [x, y, z]
                        route_at = time.monotonic() + args.settle
                    else:
                        corrections.append([x, y, z])
                        current = [x, y, z]

            now = time.monotonic()
            if first_position is None and now - started > args.join_timeout:
                raise RuntimeError("initial position timeout")

            if (
                first_position is not None
                and next_move is None
                and done_at is None
                and route_at is not None
                and now >= route_at
            ):
                next_move = now

            if next_move is not None and now >= next_move:
                assert current is not None
                target_x, target_y, target_z = waypoints[waypoint]
                vx = target_x - current[0]
                vy = target_y - current[1]
                vz = target_z - current[2]
                distance = math.sqrt(vx * vx + vy * vy + vz * vz)

                if distance <= args.step:
                    current = [target_x, target_y, target_z]
                    waypoint += 1
                else:
                    current = [
                        current[0] + args.step * vx / distance,
                        current[1] + args.step * vy / distance,
                        current[2] + args.step * vz / distance,
                    ]

                wire.send(
                    tp.PLAY_MOVE_POSROT,
                    tp.build_move_posrot(
                        current[0],
                        current[1],
                        current[2],
                        yaw=0.0,
                        pitch=0.0,
                        on_ground=False,
                    ),
                )
                wire.send(PLAY_TICK_END)

                if waypoint >= len(waypoints):
                    next_move = None
                    done_at = now + args.tail
                else:
                    next_move = now + args.interval

            if done_at is not None and now >= done_at:
                break

        missing = [
            [x, z] for x, z in sorted(scored) if f"{x},{z}" not in chunks
        ]
        result = {
            "schema": 1,
            "origin": [args.origin_x, args.route_y, args.origin_z],
            "route_centers": centers,
            "score_chunks": [list(pair) for pair in sorted(scored)],
            "first_position": first_position,
            "position_packets": position_packets,
            "corrections": corrections,
            "chunk_packets": chunk_packets,
            "unique_chunks": len(chunks),
            "batch_acks": batch_acks,
            "player_loaded": player_loaded,
            "missing_score_chunks": missing,
            "duration_s": round(time.monotonic() - started, 3),
            "chunks": chunks,
        }
        Path(args.out).write_text(
            json.dumps(result, indent=2, sort_keys=True), encoding="utf-8"
        )

        print(
            "LIVE_ROUTE_CAPTURE "
            f"scored={len(scored)} missing={len(missing)} "
            f"packets={chunk_packets} unique={len(chunks)} "
            f"batchAcks={batch_acks} corrections={len(corrections)} "
            f"duration={result['duration_s']}s",
            flush=True,
        )

        if missing:
            return 3
        if corrections:
            return 4
        return 0
    finally:
        sock.close()


def compare(a_path: str, b_path: str, min_light: int) -> int:
    a = json.loads(Path(a_path).read_text(encoding="utf-8"))
    b = json.loads(Path(b_path).read_text(encoding="utf-8"))

    score_a = {tuple(pair) for pair in a["score_chunks"]}
    score_b = {tuple(pair) for pair in b["score_chunks"]}
    problems: list[str] = []
    block_diffs: list[str] = []
    biome_diffs: list[str] = []
    light_diffs: list[str] = []
    parse_failures: list[str] = []
    missing: list[str] = []
    light_scored = 0

    if score_a != score_b:
        problems.append("score-set mismatch")

    for chunk_x, chunk_z in sorted(score_a & score_b):
        key = f"{chunk_x},{chunk_z}"
        left = a["chunks"].get(key)
        right = b["chunks"].get(key)
        if left is None or right is None:
            missing.append(key)
            continue
        if "parse_error" in left or "parse_error" in right:
            parse_failures.append(key)
            continue

        if left["blocks_sha256"] != right["blocks_sha256"]:
            block_diffs.append(key)
        if left["biome_wire_sha256"] != right["biome_wire_sha256"]:
            biome_diffs.append(key)

        left_light = left.get("light_sha256")
        right_light = right.get("light_sha256")
        if left_light and right_light:
            light_scored += 1
            if left_light != right_light:
                light_diffs.append(key)

    if missing:
        problems.append(f"missing scored chunks={len(missing)}")
    if parse_failures:
        problems.append(f"parse failures={len(parse_failures)}")
    if block_diffs:
        problems.append(f"block diffs={len(block_diffs)}")
    if biome_diffs:
        problems.append(f"biome wire diffs={len(biome_diffs)}")
    if light_diffs:
        problems.append(f"light wire diffs={len(light_diffs)}")
    if light_scored < min_light:
        problems.append(f"exact-light coverage={light_scored}<{min_light}")

    for label, capture_data in (("a", a), ("b", b)):
        if capture_data.get("corrections"):
            problems.append(
                f"{label} route corrections={len(capture_data['corrections'])}"
            )
        if int(capture_data.get("batch_acks", 0)) <= 0:
            problems.append(f"{label} observed no chunk-batch acknowledgements")
        if not capture_data.get("player_loaded"):
            problems.append(f"{label} never sent PlayerLoaded")

    summary = {
        "schema": 1,
        "score_chunks": len(score_a & score_b),
        "block_diffs": len(block_diffs),
        "biome_wire_diffs": len(biome_diffs),
        "light_scored": light_scored,
        "light_wire_diffs": len(light_diffs),
        "parse_failures": len(parse_failures),
        "missing": len(missing),
        "block_diff_chunks": block_diffs[:20],
        "biome_diff_chunks": biome_diffs[:20],
        "light_diff_chunks": light_diffs[:20],
        "parse_failure_chunks": parse_failures[:20],
        "missing_chunks": missing[:20],
        "problems": problems,
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 1 if problems else 0


def encode_varint(value: int) -> bytes:
    return tp.encode_varint(value)


def pack_for_test(values: list[int], bits: int) -> bytes:
    per_long = 64 // bits
    longs = [0] * ((len(values) + per_long - 1) // per_long)
    mask = (1 << bits) - 1
    for index, value in enumerate(values):
        longs[index // per_long] |= (
            value & mask
        ) << ((index % per_long) * bits)
    return b"".join(struct.pack(">Q", word) for word in longs)


def self_test() -> int:
    block_values = [7 if i % 2 == 0 else 9 for i in range(BLOCKS_PER_SECTION)]
    block_container = (
        bytes([4])
        + encode_varint(2)
        + encode_varint(7)
        + encode_varint(9)
        + pack_for_test([i % 2 for i in range(BLOCKS_PER_SECTION)], 4)
    )
    biome_container = bytes([0]) + encode_varint(3)
    one_section = struct.pack(">hh", 2048, 0) + block_container + biome_container
    section_blob = one_section * OVERWORLD_SECTIONS

    light = b"\x00" * 6
    packet_body = (
        struct.pack(">ii", 2, -3)
        + encode_varint(0)
        + encode_varint(len(section_blob))
        + section_blob
        + encode_varint(0)
        + light
    )
    digest = digest_chunk(packet_body)

    expected = hashlib.sha256()
    for _ in range(OVERWORLD_SECTIONS):
        expected.update(struct.pack(">hh", 2048, 0))
        for state_id in block_values:
            expected.update(struct.pack(">I", state_id))

    assert digest["blocks_sha256"] == expected.hexdigest()
    assert digest["light_bytes"] == 6
    assert len(digest["sections"]) == OVERWORLD_SECTIONS

    client_info = build_client_information(2)
    assert client_info == (
        b"\x05en_us\x02\x00\x01\x00\x01\x00\x01\x00"
    )
    assert build_brand_payload() == (
        b"\x0fminecraft:brand\x07vanilla"
    )
    # Protocol 776 ConfigurationProtocols SERVERBOUND registration order:
    # client_information=0, cookie_response=1, custom_payload=2.
    # Clientbound custom_payload is a different table/id and must not leak here.
    assert CONFIG_CLIENT_INFO == 0x00
    assert CONFIG_CUSTOM_PAYLOAD == 0x02
    assert CONFIG_KEEPALIVE == 0x04

    centers, scored = route_score_set(0.5, 0.5, 64.0, 64.0, 2)
    assert centers == [[0, 0], [4, 0], [4, 4]]
    assert len(scored) == 65

    print(
        "LIVE_ROUTE_SELF_TEST=PASS "
        "sections=24 semanticBlockPalette=PASS scoreChunks=65",
        flush=True,
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    capture_parser = sub.add_parser("capture")
    capture_parser.add_argument("--host", default="127.0.0.1")
    capture_parser.add_argument("--port", type=int, required=True)
    capture_parser.add_argument("--name", default="RouteBot")
    capture_parser.add_argument("--out", required=True)
    capture_parser.add_argument("--origin-x", type=float, default=0.5)
    capture_parser.add_argument("--origin-z", type=float, default=0.5)
    capture_parser.add_argument("--route-y", type=float, default=200.0)
    capture_parser.add_argument("--dx", type=float, default=64.0)
    capture_parser.add_argument("--dz", type=float, default=64.0)
    capture_parser.add_argument("--score-radius", type=int, default=2)
    capture_parser.add_argument("--view-distance", type=int, default=2)
    capture_parser.add_argument("--step", type=float, default=0.45)
    capture_parser.add_argument("--interval", type=float, default=0.05)
    capture_parser.add_argument("--settle", type=float, default=20.0)
    capture_parser.add_argument("--tail", type=float, default=20.0)
    capture_parser.add_argument("--join-timeout", type=float, default=120.0)
    capture_parser.add_argument("--start-tolerance", type=float, default=2.5)
    capture_parser.add_argument("--y-tolerance", type=float, default=8.0)
    capture_parser.add_argument(
        "--ascend-to-route-y",
        action="store_true",
        help="accept natural first-spawn Y and smoothly ascend/descend to route-y",
    )
    capture_parser.add_argument("--chunks-per-tick-ack", type=float, default=20.0)

    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("a")
    compare_parser.add_argument("b")
    compare_parser.add_argument("--min-light", type=int, default=50)

    sub.add_parser("self-test")

    args = parser.parse_args()
    if args.command == "capture":
        return capture(args)
    if args.command == "compare":
        return compare(args.a, args.b, args.min_light)
    return self_test()


if __name__ == "__main__":
    raise SystemExit(main())

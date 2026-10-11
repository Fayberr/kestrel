#!/usr/bin/env python3
"""Kestrel teleport bot: protocol client for chunk-loading and teleport benchmarks.

Teleports by sending a serverbound move packet to (x, y, z) directly
(teleport-chunks pattern: one big move, server accepts or rubber-bands;
no Elytra/rockets simulation).

Usage:
  python3 harness/tools/tp-bot.py --host 127.0.0.1 --port 25565 \\
      --x 1000.0 --y 70.0 --z 1000.0 --stay 60.0 --name TpBot1

Exit codes:
  0: held the full stay window in play (clean exit)
  1: connection or login phase failure
  2: login succeeded but disconnected during play
"""

import argparse
import hashlib
import socket
import struct
import sys
import time
import uuid
import zlib

PROTOCOL = 776

# Packet IDs (26.2 ground truth matching load-bot.py and PacketIds.java)
LOGIN_ACK = 0x03
CONFIG_FINISH = 0x03
CONFIG_SELECT_KNOWN_SERVERBOUND = 0x07
CONFIG_SELECT_KNOWN_CLIENTBOUND = 0x0E

PLAY_ACCEPT_TELEPORT = 0x00
PLAY_CHUNK_BATCH_RECEIVED = 0x0B
PLAY_MOVE_POS = 0x1E
PLAY_MOVE_POSROT = 0x1F
PLAY_MOVE_ROT = 0x20
PLAY_PLAYER_LOADED = 0x2C
PLAY_KEEPALIVE_OUT = 0x1C

PLAY_CHUNK_BATCH_FINISHED = 0x0B
PLAY_DISCONNECT = 0x1D
PLAY_DISCONNECT_ALT = 0x20
PLAY_KEEPALIVE_IN = 0x2C
PLAY_CHUNK = 0x2D
PLAY_POSITION = 0x48


def encode_varint(value: int) -> bytes:
    out = bytearray()
    value &= 0xFFFFFFFF
    while True:
        bits = value & 0x7F
        value >>= 7
        if value:
            out.append(bits | 0x80)
        else:
            out.append(bits)
            break
    return bytes(out)


def decode_varint(buf: bytes, pos: int = 0):
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            return None, pos
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if not (byte & 0x80):
            return result, pos
        if shift >= 35:
            raise ValueError("varint too long")


def encode_string(text: str) -> bytes:
    raw = text.encode("utf-8")
    return encode_varint(len(raw)) + raw


def decode_string(buf: bytes, pos: int = 0):
    length, pos = decode_varint(buf, pos)
    if length is None or pos + length > len(buf):
        raise ValueError("truncated string in buffer")
    return buf[pos:pos + length].decode("utf-8", "replace"), pos + length


def parse_known_packs(body: bytes):
    pos = 0
    count, pos = decode_varint(body, pos)
    if count is None:
        return []
    packs = []
    for _ in range(count):
        ns, pos = decode_string(body, pos)
        pid, pos = decode_string(body, pos)
        ver, pos = decode_string(body, pos)
        packs.append((ns, pid, ver))
    return packs


def encode_known_packs(packs):
    out = encode_varint(len(packs))
    for ns, pid, ver in packs:
        out += encode_string(ns) + encode_string(pid) + encode_string(ver)
    return out


def build_handshake(protocol: int, host: str, port: int, next_state: int = 2) -> bytes:
    return (encode_varint(protocol)
            + encode_string(host)
            + struct.pack(">H", port)
            + encode_varint(next_state))


def decode_handshake(body: bytes):
    pos = 0
    protocol, pos = decode_varint(body, pos)
    host, pos = decode_string(body, pos)
    port = struct.unpack(">H", body[pos:pos + 2])[0]
    pos += 2
    next_state, pos = decode_varint(body, pos)
    return protocol, host, port, next_state


def offline_uuid(name: str) -> uuid.UUID:
    """Minecraft offline-mode UUID (Java UUID.nameUUIDFromBytes)."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    # java.util.UUID.nameUUIDFromBytes: RFC-4122 variant + version 3.
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def build_login_start(name: str, offline_uuid: uuid.UUID) -> bytes:
    # Vanilla 26.2 ServerboundHelloPacket decodes the name with readUtf(16).
    # Enforce the protocol limit here so Kestrel's more permissive decoder
    # cannot hide an invalid client harness username.
    if len(name) > 16:
        raise ValueError(f"login name exceeds Vanilla 16-character limit: {name!r}")
    return encode_string(name) + offline_uuid.bytes


def decode_login_start(body: bytes):
    name, pos = decode_string(body, 0)
    player_uuid = uuid.UUID(bytes=body[pos:pos + 16])
    return name, player_uuid


def build_login_ack() -> bytes:
    return b""


def decode_login_ack(body: bytes):
    if len(body) != 0:
        raise ValueError("login_ack must have 0-length body")
    return True


def build_config_finish() -> bytes:
    return b""


def decode_config_finish(body: bytes):
    if len(body) != 0:
        raise ValueError("config_finish must have 0-length body")
    return True


def build_move_posrot(x: float, y: float, z: float, yaw: float = 0.0, pitch: float = 0.0, on_ground: bool = True) -> bytes:
    flags = 0x01 if on_ground else 0x00
    return struct.pack(">dddff", x, y, z, yaw, pitch) + bytes([flags])


def decode_move_posrot(body: bytes):
    if len(body) < 33:
        raise ValueError(f"move_posrot body too short: {len(body)} < 33")
    x, y, z, yaw, pitch = struct.unpack(">dddff", body[:32])
    on_ground = bool(body[32] & 0x01)
    return x, y, z, yaw, pitch, on_ground


def build_move_pos(x: float, y: float, z: float, on_ground: bool = True) -> bytes:
    flags = 0x01 if on_ground else 0x00
    return struct.pack(">ddd", x, y, z) + bytes([flags])


def decode_move_pos(body: bytes):
    if len(body) < 25:
        raise ValueError(f"move_pos body too short: {len(body)} < 25")
    x, y, z = struct.unpack(">ddd", body[:24])
    on_ground = bool(body[24] & 0x01)
    return x, y, z, on_ground


def build_keepalive(keepalive_id: int) -> bytes:
    return struct.pack(">q", keepalive_id)


def decode_keepalive(body: bytes) -> int:
    if len(body) != 8:
        raise ValueError(f"keepalive body must be 8 bytes, got {len(body)}")
    return struct.unpack(">q", body)[0]


def build_accept_teleport(teleport_id: int) -> bytes:
    return encode_varint(teleport_id)


def decode_accept_teleport(body: bytes) -> int:
    tid, _ = decode_varint(body, 0)
    if tid is None:
        raise ValueError("failed to decode teleport_id varint")
    return tid


def build_clientbound_position(teleport_id: int, x: float, y: float, z: float, yaw: float = 0.0, pitch: float = 0.0) -> bytes:
    return (encode_varint(teleport_id)
            + struct.pack(">ddd", x, y, z)
            + struct.pack(">ddd", 0.0, 0.0, 0.0)
            + struct.pack(">ff", yaw, pitch)
            + struct.pack(">i", 0))


def decode_clientbound_position(body: bytes):
    teleport_id, pos = decode_varint(body, 0)
    x, y, z = struct.unpack(">ddd", body[pos:pos + 24])
    pos += 24
    pos += 24  # delta (3 doubles)
    yaw, pitch = struct.unpack(">ff", body[pos:pos + 8])
    pos += 8
    pos += 4  # relatives (int)
    return teleport_id, x, y, z, yaw, pitch


def frame_packet(pkt_id: int, body: bytes = b"", threshold: int = -1) -> bytes:
    data = encode_varint(pkt_id) + body
    if threshold >= 0:
        if len(data) < threshold:
            payload = b"\x00" + data
        else:
            payload = encode_varint(len(data)) + zlib.compress(data)
    else:
        payload = data
    return encode_varint(len(payload)) + payload


def unframe_packet(buf: bytearray, threshold: int = -1):
    length, pos = decode_varint(bytes(buf), 0)
    if length is not None and len(buf) >= pos + length:
        payload = bytes(buf[pos:pos + length])
        del buf[:pos + length]
        if threshold >= 0:
            dlen, dpos = decode_varint(payload, 0)
            data = payload[dpos:] if dlen == 0 else zlib.decompress(payload[dpos:])
        else:
            data = payload
        pkt_id, ipos = decode_varint(data, 0)
        return pkt_id, data[ipos:]
    return None


class Wire:
    def __init__(self, sock):
        self.sock = sock
        self.buf = bytearray()
        self.threshold = -1

    def _fill(self, deadline: float):
        remaining = deadline - time.time()
        if remaining <= 0:
            raise socket.timeout("timed out waiting for packet")
        self.sock.settimeout(max(0.1, remaining))
        chunk = self.sock.recv(65536)
        if not chunk:
            raise ConnectionError("server closed connection")
        self.buf += chunk

    def read_packet(self, timeout: float = 30.0):
        deadline = time.time() + timeout
        while True:
            pkt = unframe_packet(self.buf, self.threshold)
            if pkt is not None:
                return pkt
            if time.time() > deadline:
                raise TimeoutError("packet read timed out")
            try:
                self._fill(deadline)
            except socket.timeout:
                raise TimeoutError("packet read timed out")

    def send(self, pkt_id: int, body: bytes = b""):
        wire_bytes = frame_packet(pkt_id, body, self.threshold)
        self.sock.sendall(wire_bytes)


def run_self_test() -> int:
    print("[self-test] running packet encode/decode round-trip verification...", flush=True)
    tests_run = 0

    # 1. VarInt round-trip
    test_ints = [0, 1, 2, 127, 128, 255, 256, 776, 16383, 16384, 2097151, 2097152, 2147483647]
    for val in test_ints:
        encoded = encode_varint(val)
        decoded, pos = decode_varint(encoded, 0)
        assert decoded == val, f"varint mismatch for {val}: got {decoded}"
        assert pos == len(encoded), f"varint trailing bytes for {val}"
    assert encode_varint(0) == b"\x00"
    assert encode_varint(1) == b"\x01"
    assert encode_varint(127) == b"\x7f"
    assert encode_varint(128) == b"\x80\x01"
    tests_run += 1
    print("[self-test] PASS: VarInt round-trip (boundary values 0..max_int32)", flush=True)

    # 2. String round-trip
    test_strings = ["", "a", "127.0.0.1", "TpBot1", "minecraft:core", "unicode: §lKestrel§r"]
    for s in test_strings:
        enc = encode_string(s)
        dec, pos = decode_string(enc, 0)
        assert dec == s, f"string mismatch: '{s}' != '{dec}'"
        assert pos == len(enc)
    tests_run += 1
    print("[self-test] PASS: String round-trip", flush=True)

    # 3. Handshake packet
    hs_body = build_handshake(776, "127.0.0.1", 25565, 2)
    proto, host, port, next_state = decode_handshake(hs_body)
    assert proto == 776 and host == "127.0.0.1" and port == 25565 and next_state == 2
    tests_run += 1
    print("[self-test] PASS: Handshake packet (0x00, proto=776, host, port, state=2)", flush=True)

    # 4. Login Start packet
    test_uuid = offline_uuid("TpBot")
    assert str(test_uuid) == "a0af1e5e-5701-3313-91bd-299e5cc5d7ac"
    ls_body = build_login_start("TpBot", test_uuid)
    ls_name, ls_uuid = decode_login_start(ls_body)
    assert ls_name == "TpBot" and ls_uuid == test_uuid
    tests_run += 1
    print("[self-test] PASS: Login Start packet (0x00, name, offline_uuid)", flush=True)

    # 5. Login Ack packet
    ack_body = build_login_ack()
    assert decode_login_ack(ack_body) is True
    tests_run += 1
    print("[self-test] PASS: Login Ack packet (0x03)", flush=True)

    # 6. Config Select Known Packs
    packs = [("minecraft", "core", "1.21.2"), ("kestrel", "test", "0.1")]
    kp_body = encode_known_packs(packs)
    dec_packs = parse_known_packs(kp_body)
    assert dec_packs == packs
    assert parse_known_packs(encode_known_packs([])) == []
    tests_run += 1
    print("[self-test] PASS: Config Select Known Packs (0x07/0x0E)", flush=True)

    # 7. Config Finish packet
    fin_body = build_config_finish()
    assert decode_config_finish(fin_body) is True
    tests_run += 1
    print("[self-test] PASS: Config Finish packet (0x03)", flush=True)

    # 8. Play Move PosRot packet (0x1F)
    mv_pr = build_move_posrot(1000.5, 70.0, -500.25, 90.0, -15.0, on_ground=True)
    assert len(mv_pr) == 33
    x, y, z, yaw, pitch, on_ground = decode_move_posrot(mv_pr)
    assert abs(x - 1000.5) < 1e-6 and abs(y - 70.0) < 1e-6 and abs(z - (-500.25)) < 1e-6
    assert abs(yaw - 90.0) < 1e-5 and abs(pitch - (-15.0)) < 1e-5
    assert on_ground is True
    # Test on_ground=False
    _, _, _, _, _, off_ground = decode_move_posrot(build_move_posrot(0, 0, 0, on_ground=False))
    assert off_ground is False
    tests_run += 1
    print("[self-test] PASS: Play Move PosRot packet (0x1F, 33-byte wire format)", flush=True)

    # 9. Play Move Pos packet (0x1E)
    mv_p = build_move_pos(250.0, 64.0, 250.0, on_ground=True)
    assert len(mv_p) == 25
    px, py, pz, pground = decode_move_pos(mv_p)
    assert abs(px - 250.0) < 1e-6 and abs(py - 64.0) < 1e-6 and abs(pz - 250.0) < 1e-6
    assert pground is True
    tests_run += 1
    print("[self-test] PASS: Play Move Pos packet (0x1E, 25-byte wire format)", flush=True)

    # 10. Play KeepAlive packet (0x1C/0x2C)
    for ka_id in [0, 1234567890123456, -9876543210]:
        ka_body = build_keepalive(ka_id)
        assert len(ka_body) == 8
        assert decode_keepalive(ka_body) == ka_id
    tests_run += 1
    print("[self-test] PASS: Play KeepAlive packet (0x1C/0x2C, 8-byte long)", flush=True)

    # 11. Play Accept Teleport packet (0x00)
    for tid in [0, 1, 42, 9999]:
        at_body = build_accept_teleport(tid)
        assert decode_accept_teleport(at_body) == tid
    tests_run += 1
    print("[self-test] PASS: Play Accept Teleport packet (0x00, varint teleport_id)", flush=True)

    # 12. Play Clientbound Position packet (0x48)
    cb_pos = build_clientbound_position(17, 100.0, 64.0, 200.0, 180.0, 0.0)
    cid, cx, cy, cz, cyaw, cpitch = decode_clientbound_position(cb_pos)
    assert cid == 17
    assert abs(cx - 100.0) < 1e-6 and abs(cy - 64.0) < 1e-6 and abs(cz - 200.0) < 1e-6
    assert abs(cyaw - 180.0) < 1e-5 and abs(cpitch - 0.0) < 1e-5
    tests_run += 1
    print("[self-test] PASS: Play Clientbound Position packet (0x48)", flush=True)

    # 13. Wire framing uncompressed round-trip
    test_body = build_move_posrot(100.0, 70.0, 100.0)
    framed = frame_packet(PLAY_MOVE_POSROT, test_body, threshold=-1)
    buf = bytearray(framed)
    unframed_id, unframed_body = unframe_packet(buf, threshold=-1)
    assert unframed_id == PLAY_MOVE_POSROT and unframed_body == test_body
    assert len(buf) == 0
    tests_run += 1
    print("[self-test] PASS: Wire framing uncompressed round-trip", flush=True)

    # 14. Wire framing compressed round-trip
    # (a) Small packet under threshold
    small_body = b"hello_world"
    f_small = frame_packet(0x05, small_body, threshold=64)
    buf_s = bytearray(f_small)
    s_id, s_body = unframe_packet(buf_s, threshold=64)
    assert s_id == 0x05 and s_body == small_body and len(buf_s) == 0

    # (b) Large packet over threshold
    large_body = bytes(range(256)) * 4
    f_large = frame_packet(0x09, large_body, threshold=64)
    buf_l = bytearray(f_large)
    l_id, l_body = unframe_packet(buf_l, threshold=64)
    assert l_id == 0x09 and l_body == large_body and len(buf_l) == 0
    tests_run += 1
    print("[self-test] PASS: Wire framing compressed round-trip (< and >= threshold)", flush=True)

    # 15. In-memory socketpair full session simulation
    client_s, server_s = socket.socketpair()
    try:
        c_wire = Wire(client_s)
        s_wire = Wire(server_s)

        # Client sends handshake + login start
        c_wire.send(0x00, build_handshake(PROTOCOL, "127.0.0.1", 25565, 2))
        c_wire.send(0x00, build_login_start("TpBot", test_uuid))

        # Server reads handshake + login start
        hs_id, hs_raw = s_wire.read_packet()
        assert hs_id == 0x00 and decode_handshake(hs_raw)[0] == PROTOCOL
        ls_id, ls_raw = s_wire.read_packet()
        assert ls_id == 0x00 and decode_login_start(ls_raw)[0] == "TpBot"

        # Server sets compression threshold 256
        s_wire.send(0x03, encode_varint(256))
        comp_id, comp_raw = c_wire.read_packet()
        assert comp_id == 0x03
        c_wire.threshold = decode_varint(comp_raw, 0)[0]
        s_wire.threshold = 256

        # Server sends LoginFinished -> Client sends LOGIN_ACK
        s_wire.send(0x02, encode_string("TpBot") + test_uuid.bytes)
        fin_id, _ = c_wire.read_packet()
        assert fin_id == 0x02
        c_wire.send(LOGIN_ACK, build_login_ack())
        ack_id, ack_raw = s_wire.read_packet()
        assert ack_id == LOGIN_ACK and len(ack_raw) == 0

        # Config phase: server sends select known packs -> client echoes
        s_wire.send(CONFIG_SELECT_KNOWN_CLIENTBOUND, encode_known_packs(packs))
        kp_id, kp_raw = c_wire.read_packet()
        assert kp_id == CONFIG_SELECT_KNOWN_CLIENTBOUND
        c_wire.send(CONFIG_SELECT_KNOWN_SERVERBOUND, encode_known_packs(parse_known_packs(kp_raw)))
        sk_id, sk_raw = s_wire.read_packet()
        assert sk_id == CONFIG_SELECT_KNOWN_SERVERBOUND and parse_known_packs(sk_raw) == packs

        # Config finish
        s_wire.send(CONFIG_FINISH, build_config_finish())
        cfin_id, _ = c_wire.read_packet()
        assert cfin_id == CONFIG_FINISH
        c_wire.send(CONFIG_FINISH, build_config_finish())
        sfin_id, _ = s_wire.read_packet()
        assert sfin_id == CONFIG_FINISH

        # Play phase: client teleports to (1000.0, 70.0, 1000.0)
        c_wire.send(PLAY_MOVE_POSROT, build_move_posrot(1000.0, 70.0, 1000.0))
        mv_id, mv_raw = s_wire.read_packet()
        assert mv_id == PLAY_MOVE_POSROT
        tx, ty, tz, _, _, tg = decode_move_posrot(mv_raw)
        assert abs(tx - 1000.0) < 1e-6 and abs(ty - 70.0) < 1e-6 and abs(tz - 1000.0) < 1e-6 and tg is True

        # Keepalive exchange
        test_ka = 987654321
        s_wire.send(PLAY_KEEPALIVE_IN, build_keepalive(test_ka))
        ka_in_id, ka_in_body = c_wire.read_packet()
        assert ka_in_id == PLAY_KEEPALIVE_IN and len(ka_in_body) == 8
        c_wire.send(PLAY_KEEPALIVE_OUT, ka_in_body)
        ka_out_id, ka_out_body = s_wire.read_packet()
        assert ka_out_id == PLAY_KEEPALIVE_OUT and decode_keepalive(ka_out_body) == test_ka
    finally:
        client_s.close()
        server_s.close()

    tests_run += 1
    print("[self-test] PASS: In-memory loopback simulated login + teleport + keepalive", flush=True)

    print(f"SELF-TEST PASSED: {tests_run}/{tests_run} checks OK", flush=True)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Kestrel teleport bot")
    ap.add_argument("--host", default="127.0.0.1", help="server host (default: 127.0.0.1)")
    ap.add_argument("--port", type=int, default=25565, help="server port (default: 25565)")
    ap.add_argument("--x", type=float, default=0.0, help="target X coordinate (default: 0.0)")
    ap.add_argument("--y", type=float, default=64.0, help="target Y coordinate (default: 64.0)")
    ap.add_argument("--z", type=float, default=0.0, help="target Z coordinate (default: 0.0)")
    ap.add_argument("--stay", type=float, default=60.0, help="stay duration in seconds (default: 60.0)")
    ap.add_argument("--name", default="TpBot", help="bot username (default: TpBot)")
    ap.add_argument("--debug", action="store_true", help="enable packet logging")
    ap.add_argument("--self-test", action="store_true", help="run self-test without network and exit")
    args = ap.parse_args()

    if args.self_test:
        return run_self_test()

    player_uuid = offline_uuid(args.name)
    sock = None
    phase = "login"
    play_deadline = None
    join_latency = -1.0
    chunk_packets = 0
    unique_chunks = set()
    batch_acks = 0
    player_loaded_sent = False
    t0 = time.time()

    def emit_stats():
        print(
            f"TPBOT_STATS chunks={chunk_packets} uniqueChunks={len(unique_chunks)} "
            f"batchAcks={batch_acks} playerLoaded={1 if player_loaded_sent else 0}",
            flush=True,
        )

    try:
        sock = socket.create_connection((args.host, args.port), timeout=15)
        wire = Wire(sock)

        # 1. Handshake -> Login state
        wire.send(0x00, build_handshake(PROTOCOL, args.host, args.port, 2))
        # 2. Login Start
        wire.send(0x00, build_login_start(args.name, player_uuid))

        while True:
            # Check if deadline passed before waiting on read
            if play_deadline is not None and time.time() >= play_deadline:
                print(f"[tp-bot] {args.name} stayed full window ({args.stay:.1f}s)", flush=True)
                emit_stats()
                print("BOT_EXIT=0", flush=True)
                return 0

            # Dynamic timeout: don't block past stay deadline
            timeout = 30.0
            if play_deadline is not None:
                remaining = play_deadline - time.time()
                if remaining <= 0:
                    print(f"[tp-bot] {args.name} stayed full window ({args.stay:.1f}s)", flush=True)
                    emit_stats()
                    print("BOT_EXIT=0", flush=True)
                    return 0
                timeout = min(30.0, max(0.2, remaining))

            pkt_id, body = wire.read_packet(timeout=timeout)
            if args.debug:
                print(f"[tp-bot] phase={phase} id=0x{pkt_id:02x} len={len(body)}", flush=True)

            if phase == "login":
                if pkt_id == 0x03 and len(body) <= 5:
                    threshold, _ = decode_varint(body, 0)
                    wire.threshold = threshold
                elif pkt_id == 0x02:
                    wire.send(LOGIN_ACK, build_login_ack())
                    phase = "config"

            elif phase == "config":
                if pkt_id == CONFIG_SELECT_KNOWN_CLIENTBOUND:
                    try:
                        packs = parse_known_packs(body)
                    except Exception:
                        packs = []
                    wire.send(CONFIG_SELECT_KNOWN_SERVERBOUND, encode_known_packs(packs))
                elif pkt_id == CONFIG_FINISH and len(body) == 0:
                    wire.send(CONFIG_FINISH, build_config_finish())
                    phase = "play"
                    join_latency = time.time() - t0
                    play_deadline = time.time() + args.stay

                    # Teleport by sending serverbound move packet directly to (x, y, z)
                    move_body = build_move_posrot(args.x, args.y, args.z, yaw=0.0, pitch=0.0, on_ground=True)
                    wire.send(PLAY_MOVE_POSROT, move_body)

                    # Required benchmark parse line
                    print(f"TPBOT join={join_latency:.2f}s pos=({args.x},{args.y},{args.z}) stay={args.stay:.1f}s", flush=True)

                    if args.stay <= 0:
                        print(f"[tp-bot] {args.name} stayed full window ({args.stay:.1f}s)", flush=True)
                        emit_stats()
                        print("BOT_EXIT=0", flush=True)
                        return 0

            else:  # phase == "play"
                if pkt_id == PLAY_CHUNK_BATCH_FINISHED:
                    batch_acks += 1
                    wire.send(PLAY_CHUNK_BATCH_RECEIVED, struct.pack(">f", 10.0))
                    if args.debug:
                        print(f"[tp-bot] acknowledged chunk batch #{batch_acks}", flush=True)
                elif pkt_id == PLAY_CHUNK and len(body) >= 8:
                    chunk_packets += 1
                    cx, cz = struct.unpack(">ii", body[:8])
                    unique_chunks.add((cx, cz))
                    if args.debug:
                        print(f"[tp-bot] chunk ({cx},{cz}) packets={chunk_packets}", flush=True)
                elif len(body) == 8 and pkt_id == PLAY_KEEPALIVE_IN:
                    wire.send(PLAY_KEEPALIVE_OUT, body)
                    if args.debug:
                        print(f"[tp-bot] keepalive reply 0x{PLAY_KEEPALIVE_OUT:02x} id={body.hex()}", flush=True)
                elif pkt_id in (PLAY_DISCONNECT, PLAY_DISCONNECT_ALT):
                    print(f"[tp-bot] disconnected by server (id=0x{pkt_id:02x})", flush=True)
                    print("BOT_EXIT=2", flush=True)
                    return 2
                elif pkt_id == PLAY_POSITION:
                    try:
                        teleport_id, _ = decode_varint(body, 0)
                        if teleport_id is not None:
                            wire.send(PLAY_ACCEPT_TELEPORT, build_accept_teleport(teleport_id))
                            if not player_loaded_sent:
                                wire.send(PLAY_PLAYER_LOADED)
                                player_loaded_sent = True
                            if args.debug:
                                print(f"[tp-bot] accepted server teleport id={teleport_id}", flush=True)
                    except Exception:
                        pass

                if play_deadline is not None and time.time() >= play_deadline:
                    print(f"[tp-bot] {args.name} stayed full window ({args.stay:.1f}s)", flush=True)
                    emit_stats()
                    print("BOT_EXIT=0", flush=True)
                    return 0

    except (TimeoutError, ConnectionError) as exc:
        if phase == "play":
            if play_deadline is not None and time.time() >= play_deadline:
                print(f"[tp-bot] {args.name} stayed full window ({args.stay:.1f}s)", flush=True)
                emit_stats()
                print("BOT_EXIT=0", flush=True)
                return 0
            print(f"[tp-bot] lost connection after login: {exc}", flush=True)
            print("BOT_EXIT=2", flush=True)
            return 2
        print(f"[tp-bot] login failed in phase {phase}: {exc}", flush=True)
        print("BOT_EXIT=1", flush=True)
        return 1
    except Exception as exc:
        code = 2 if phase == "play" else 1
        print(f"[tp-bot] failure: {exc}", flush=True)
        print(f"BOT_EXIT={code}", flush=True)
        return code
    finally:
        if sock:
            try:
                sock.close()
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())

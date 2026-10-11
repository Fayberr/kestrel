#!/usr/bin/env python3
"""Kestrel load bot: behavior-driven 26.2 protocol client for benchmarks.

Behaviors (composable, chosen per scenario):
  idle  - stand still, answer keepalive (baseline connection cost)
  spin  - rotate yaw in place (movement packets, no chunk streaming)
  walk  - walk +X with jumping (movement + collision + chunk streaming)
  dig   - break the block below every few seconds (block updates)
  place - place a block below every few seconds (use-item-on + acks)

Usage:
  python3 load-bot.py --host 127.0.0.1 --port 25565 --name LoadBot1 \\
      --stay 60 --behavior walk --seed 7

Exit codes: 0 held the full window in play, 1 connection/login failure,
2 login succeeded but disconnected early.
"""

import argparse
import socket
import struct
import sys
import time
import uuid
import zlib

PROTOCOL = 776


def encode_varint(value):
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


def decode_varint(buf, pos=0):
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


def encode_string(text):
    raw = text.encode("utf-8")
    return encode_varint(len(raw)) + raw


def decode_string(buf, pos):
    length, pos = decode_varint(body, pos) if False else decode_varint(buf, pos)
    return buf[pos:pos + length].decode("utf-8", "replace"), pos + length


class Wire:
    def __init__(self, sock):
        self.sock = sock
        self.buf = bytearray()
        self.threshold = -1

    def _fill(self, deadline):
        self.sock.settimeout(max(0.1, deadline - time.time()))
        chunk = self.sock.recv(65536)
        if not chunk:
            raise ConnectionError("server closed connection")
        self.buf += chunk

    def read_packet(self, timeout=30.0):
        deadline = time.time() + timeout
        while True:
            length, pos = decode_varint(bytes(self.buf), 0)
            if length is not None and len(self.buf) >= pos + length:
                payload = bytes(self.buf[pos:pos + length])
                del self.buf[:pos + length]
                if self.threshold >= 0:
                    dlen, dpos = decode_varint(payload, 0)
                    data = payload[dpos:] if dlen == 0 else zlib.decompress(payload[dpos:])
                else:
                    data = payload
                pkt_id, ipos = decode_varint(data, 0)
                return pkt_id, data[ipos:]
            if time.time() > deadline:
                raise TimeoutError("packet read timed out")
            try:
                self._fill(deadline)
            except socket.timeout:
                raise TimeoutError("packet read timed out")

    def send(self, pkt_id, body=b""):
        data = encode_varint(pkt_id) + body
        if self.threshold >= 0:
            payload = (b"\x00" + data) if len(data) < self.threshold \
                else encode_varint(len(data)) + zlib.compress(data)
        else:
            payload = data
        self.sock.sendall(encode_varint(len(payload)) + payload)


def parse_known_packs(body):
    pos = 0
    count, pos = decode_varint(body, pos)
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


# Serverbound play IDs (26.2 ground truth, PacketIds.java).
PLAY_ACCEPT_TELEPORT = 0x00
PLAY_CHUNK_BATCH_RECEIVED = 0x0B
PLAY_KEEPALIVE_OUT = 0x1C
PLAY_MOVE_POSROT = 0x1F
PLAY_MOVE_ROT = 0x20
PLAY_PLAYER_ACTION = 0x29
PLAY_PLAYER_LOADED = 0x2C
PLAY_USE_ITEM_ON = 0x42

# Clientbound play IDs
PLAY_BLOCK_CHANGED_ACK = 0x04
PLAY_BLOCK_UPDATE = 0x08
PLAY_CHUNK_BATCH_FINISHED = 0x0B
PLAY_DISCONNECT = 0x20
PLAY_KEEPALIVE_IN = 0x2C
PLAY_POSITION = 0x48


def use_item_on(hand, pos_long, face, sequence):
    pos_signed = pos_long if pos_long < (1 << 63) else pos_long - (1 << 64)
    return (encode_varint(hand)
            + struct.pack(">q", pos_signed)
            + encode_varint(face)
            + struct.pack(">fff", 0.5, 1.0, 0.5)
            + b"\x00\x00"
            + encode_varint(sequence))


class Behavior:
    """One behavior tick. Returns nothing; sends movement/action packets."""

    def __init__(self, name, seed):
        self.name = name
        self.t = 0
        self.x = 0.5 + (seed % 5)
        self.yaw = float((seed * 37) % 360)
        self.place_seq = 1
        self.last_seq = 0
        self.seen_acks = set()

    def step(self, wire, now):
        self.t += 1
        if self.name == "idle":
            return
        if self.name == "spin":
            if self.t % 5 == 0:
                self.yaw = (self.yaw + 15.0) % 360.0
                wire.send(PLAY_MOVE_ROT,
                          struct.pack(">ff", self.yaw, 0.0) + b"\x01")
        elif self.name == "walk":
            if self.t % 5 == 0:
                self.x += 0.8
                on_ground = (self.t // 5) % 4 != 3  # jump every 4th step
                move = struct.pack(">ddd", self.x, 2.0, 0.5)
                move += struct.pack(">ff", 90.0, 0.0)
                move += b"\x01" if on_ground else b"\x00"
                wire.send(PLAY_MOVE_POSROT, move)
        elif self.name == "dig":
            if self.t % 5 == 0:
                self.x += 0.4
                move = struct.pack(">ddd", self.x, 2.0, 0.5)
                move += struct.pack(">ff", 90.0, 0.0) + b"\x01"
                wire.send(PLAY_MOVE_POSROT, move)
            if self.t % 100 == 50:
                # start destroy block below feet
                bx, by, bz = int(self.x), 1, 0
                pos = ((bx & 0x3FFFFFF) << 38) | ((bz & 0x3FFFFFF) << 12) | (by & 0xFFF)
                wire.send(PLAY_PLAYER_ACTION,
                          encode_varint(0) + struct.pack(">q", pos) + b"\x00" + encode_varint(0))
            elif self.t % 100 == 90:
                bx, by, bz = int(self.x), 1, 0
                pos = ((bx & 0x3FFFFFF) << 38) | ((bz & 0x3FFFFFF) << 12) | (by & 0xFFF)
                wire.send(PLAY_PLAYER_ACTION,
                          encode_varint(2) + struct.pack(">q", pos) + b"\x00" + encode_varint(0))
        elif self.name == "place":
            if self.t % 5 == 0:
                self.x += 0.4
                move = struct.pack(">ddd", self.x, 2.0, 0.5)
                move += struct.pack(">ff", 90.0, 0.0) + b"\x01"
                wire.send(PLAY_MOVE_POSROT, move)
            if self.t % 100 == 50:
                bx, by, bz = int(self.x), 1, 0
                pos = ((bx & 0x3FFFFFF) << 38) | ((bz & 0x3FFFFFF) << 12) | (by & 0xFFF)
                self.last_seq = self.place_seq
                wire.send(PLAY_USE_ITEM_ON,
                          use_item_on(0, pos, 1, self.place_seq))
                self.place_seq += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25565)
    ap.add_argument("--name", default="LoadBot")
    ap.add_argument("--stay", type=float, default=60.0)
    ap.add_argument("--behavior", default="walk",
                    choices=["idle", "spin", "walk", "dig", "churn", "place"])
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--churn-cycles", type=int, default=3,
                    help="churn behavior only: join/leave cycles in the stay window")
    args = ap.parse_args()

    CONFIG_SELECT_KNOWN = 0x07
    CONFIG_FINISH = 0x03
    LOGIN_ACK = 0x03

    def one_session(suffix, dwell_s=0.0):
        """Run a single join->play session. Returns (join_latency_s, ok).

        dwell_s: after entering play, hold the session this long (stepping
        the behavior + answering keepalive) before returning ok. 0 = return
        immediately (used by churn before the dwell fix; kept for clarity)."""
        name = args.name + suffix
        offline_uuid = uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + name)
        behavior = Behavior(args.behavior, args.seed)
        sock = socket.create_connection((args.host, args.port), timeout=15)
        wire = Wire(sock)
        t0 = time.time()
        wire.send(0x00, encode_varint(PROTOCOL) + encode_string(args.host)
                  + struct.pack(">H", args.port) + encode_varint(2))
        wire.send(0x00, encode_string(name) + offline_uuid.bytes)
        phase = "login"
        play_deadline = None
        join_latency = -1.0
        player_loaded_sent = False
        batch_acks = 0
        try:
            while True:
                pkt_id, body = wire.read_packet(timeout=30.0)
                if phase == "login":
                    if pkt_id == 0x03 and len(body) <= 5:
                        (threshold, _) = decode_varint(body, 0)
                        wire.threshold = threshold
                    elif pkt_id == 0x02:
                        wire.send(LOGIN_ACK)
                        phase = "config"
                elif phase == "config":
                    if pkt_id == 0x0E:
                        try:
                            packs = parse_known_packs(body)
                        except Exception:
                            packs = []
                        wire.send(CONFIG_SELECT_KNOWN, encode_known_packs(packs))
                    elif pkt_id == CONFIG_FINISH and len(body) == 0:
                        wire.send(CONFIG_FINISH)
                        phase = "play"
                        join_latency = time.time() - t0
                        print(f"[load-bot] {name} entered play "
                              f"behavior={args.behavior} join={join_latency:.1f}s",
                              flush=True)
                        if args.behavior == "churn":
                            if dwell_s <= 0:
                                return join_latency, True
                            play_deadline = time.time() + dwell_s
                        else:
                            play_deadline = time.time() + args.stay
                else:
                    behavior.step(wire, time.time())
                    if pkt_id == PLAY_CHUNK_BATCH_FINISHED:
                        batch_acks += 1
                        wire.send(PLAY_CHUNK_BATCH_RECEIVED, struct.pack(">f", 10.0))
                    elif pkt_id == PLAY_POSITION:
                        teleport_id, _ = decode_varint(body, 0)
                        if teleport_id is not None:
                            wire.send(PLAY_ACCEPT_TELEPORT, encode_varint(teleport_id))
                            if not player_loaded_sent:
                                wire.send(PLAY_PLAYER_LOADED)
                                player_loaded_sent = True
                    elif len(body) == 8 and pkt_id == PLAY_KEEPALIVE_IN:
                        wire.send(PLAY_KEEPALIVE_OUT, body)
                    elif pkt_id == PLAY_DISCONNECT:
                        print("[load-bot] disconnected by server", flush=True)
                        return join_latency, False
                    elif pkt_id in (PLAY_BLOCK_CHANGED_ACK, PLAY_BLOCK_UPDATE):
                        if args.behavior == "place":
                            seq = None
                            if pkt_id == PLAY_BLOCK_CHANGED_ACK and len(body) > 0:
                                try:
                                    seq, _ = decode_varint(body, 0)
                                except Exception:
                                    seq = None
                            if seq is None and behavior.last_seq > 0:
                                seq = behavior.last_seq
                            if seq is not None and seq not in behavior.seen_acks:
                                behavior.seen_acks.add(seq)
                                print(f"[load-bot] place ack seen seq={seq}", flush=True)
                    if play_deadline is not None and time.time() > play_deadline:
                        print(
                            f"[load-bot] {name} stayed full window "
                            f"batchAcks={batch_acks} playerLoaded={1 if player_loaded_sent else 0}",
                            flush=True,
                        )
                        return join_latency, True
        except (TimeoutError, ConnectionError) as exc:
            if phase == "play":
                print(f"[load-bot] lost connection after login: {exc}", flush=True)
                return join_latency, False
            print(f"[load-bot] login failed in phase {phase}: {exc}", flush=True)
            return -1.0, False
        finally:
            try:
                sock.close()
            except OSError:
                pass

    if args.behavior == "churn":
        latencies = []
        cycles = max(1, args.churn_cycles)
        # Dwell per cycle so the server actually carries the session load
        # (previously bots disconnected the instant they entered play and
        # only slept 1s between cycles, so churn cpuLoad/rssLoad measured
        # an idle server).
        per = max(5.0, args.stay / cycles - 1.0)
        for c in range(cycles):
            lat, ok = one_session(f"_c{c}", dwell_s=per)
            if lat >= 0:
                latencies.append(lat)
            if not ok:
                print(f"[load-bot] {args.name} churn stopped early at cycle {c}",
                      flush=True)
                return 2
            time.sleep(1.0)
        avg = sum(latencies) / len(latencies) if latencies else -1
        print(f"[load-bot] {args.name} churn done "
              f"cycles={len(latencies)} avgJoin={avg:.1f}s", flush=True)
        # Distinct marker so the runner counts this bot once, not once per
        # cycle (each cycle also prints "entered play" above).
        print(f"[load-bot] {args.name} bot done", flush=True)
        return 0
    lat, ok = one_session("")
    if lat < 0:
        return 1
    if ok:
        print(f"[load-bot] {args.name} bot done", flush=True)
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())

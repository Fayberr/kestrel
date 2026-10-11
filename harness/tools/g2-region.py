#!/usr/bin/env python3
"""G2 step-2 region tool: synthetic vanilla-shaped worlds + offline census.

Stdlib only, and deliberately independent of Kestrel's Java codec (its own
NBT reader/writer, its own SimpleBitStorage packing), so the census it prints
is a real cross-check of the import-side census, not the same code twice
(preresearch/g2-memory-design.md 1.4 item 4: "the two have to agree
exactly, which also re-proves the 0.3 fix").

  g2-region.py synth  OUT_WORLD [--half 2] [--center 0,0]
      Writes OUT_WORLD/region/r.*.mca: a (2*half+1)^2 chunk square of FULL
      overworld chunks shaped like vanilla 26.2 writes them (design 0.2):
      all 24 in-range section tags including air-only [minecraft:air]
      palettes, plus light-only tags at Y=-5 and Y=20. Terrain covers every
      census bucket: air-only, single-value non-air (bedrock-free deepslate
      section), 2-16, 17-32 (a wool/concrete pillar), >256 (a redstone-wire
      state wall), cave_air pockets (air that is not minecraft:air), water.
      Also writes OUT_WORLD/synthetic.json with the parameters.

  g2-region.py census WORLD [--square cx,cz,half]
      Prints the same "census ..." line ImportCensus.line() logs at import.

Section index is (y<<8)|(z<<4)|x, values-per-long 64//bits, no straddling
(SimpleBitStorage). Disc bits: 1 state -> 0 (no data), <=16 -> 4, <=32 -> 5,
<=64 -> 6, <=128 -> 7, <=256 -> 8, else ceil(log2(n)).
"""

import argparse
import json
import math
import os
import struct
import sys
import zlib

MIN_SECTION_Y = -4
SECTIONS = 24
AIRS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
BUCKETS = ["1", "2-16", "17-32", "33-64", "65-128", "129-256", ">256"]

# ---- NBT ----------------------------------------------------------------

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE = range(7)
TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(7, 13)


class Typed:
    """Explicitly typed scalar/array for the writer."""

    def __init__(self, tag, value):
        self.tag = tag
        self.value = value


def _w_string(out, s):
    b = s.encode("utf-8")
    out += struct.pack(">H", len(b)) + b


def _tag_of(v):
    if isinstance(v, Typed):
        return v.tag
    if isinstance(v, dict):
        return TAG_COMPOUND
    if isinstance(v, str):
        return TAG_STRING
    if isinstance(v, list):
        return TAG_LIST
    if isinstance(v, int):
        return TAG_INT
    raise TypeError(type(v))


def _w_payload(out, tag, v):
    if isinstance(v, Typed):
        v = v.value
    if tag == TAG_BYTE:
        out += struct.pack(">b", v)
    elif tag == TAG_SHORT:
        out += struct.pack(">h", v)
    elif tag == TAG_INT:
        out += struct.pack(">i", v)
    elif tag == TAG_LONG:
        out += struct.pack(">q", v)
    elif tag == TAG_STRING:
        _w_string(out, v)
    elif tag == TAG_LONG_ARRAY:
        out += struct.pack(">i", len(v))
        out += struct.pack(">%dq" % len(v), *v)
    elif tag == TAG_LIST:
        if v and isinstance(v[0], Typed):
            et = v[0].tag
        elif v:
            et = _tag_of(v[0])
        else:
            et = TAG_END
        out += struct.pack(">bi", et, len(v))
        for item in v:
            _w_payload(out, et, item)
    elif tag == TAG_COMPOUND:
        for k, item in v.items():
            t = _tag_of(item)
            out += struct.pack(">b", t)
            _w_string(out, k)
            _w_payload(out, t, item)
        out += b"\x00"
    else:
        raise ValueError(tag)


def nbt_bytes(root):
    out = bytearray()
    out += struct.pack(">b", TAG_COMPOUND)
    _w_string(out, "")
    _w_payload(out, TAG_COMPOUND, root)
    return bytes(out)


class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        b = self.d[self.i:self.i + n]
        self.i += n
        return b

    def string(self):
        (n,) = struct.unpack(">H", self.take(2))
        return self.take(n).decode("utf-8", "replace")

    def payload(self, tag):
        if tag == TAG_BYTE:
            return struct.unpack(">b", self.take(1))[0]
        if tag == TAG_SHORT:
            return struct.unpack(">h", self.take(2))[0]
        if tag == TAG_INT:
            return struct.unpack(">i", self.take(4))[0]
        if tag == TAG_LONG:
            return struct.unpack(">q", self.take(8))[0]
        if tag == TAG_FLOAT:
            return struct.unpack(">f", self.take(4))[0]
        if tag == TAG_DOUBLE:
            return struct.unpack(">d", self.take(8))[0]
        if tag == TAG_BYTE_ARRAY:
            (n,) = struct.unpack(">i", self.take(4))
            return self.take(n)
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            et, n = struct.unpack(">bi", self.take(5))
            return [self.payload(et) for _ in range(n)]
        if tag == TAG_COMPOUND:
            out = {}
            while True:
                (t,) = struct.unpack(">b", self.take(1))
                if t == TAG_END:
                    return out
                k = self.string()
                out[k] = self.payload(t)
        if tag == TAG_INT_ARRAY:
            (n,) = struct.unpack(">i", self.take(4))
            return list(struct.unpack(">%di" % n, self.take(4 * n)))
        if tag == TAG_LONG_ARRAY:
            (n,) = struct.unpack(">i", self.take(4))
            return list(struct.unpack(">%dq" % n, self.take(8 * n)))
        raise ValueError("bad tag %d at %d" % (tag, self.i))


def nbt_parse(data):
    r = Reader(data)
    (t,) = struct.unpack(">b", r.take(1))
    if t != TAG_COMPOUND:
        raise ValueError("root is not a compound")
    r.string()
    return r.payload(TAG_COMPOUND)


# ---- region files -------------------------------------------------------

def write_region(path, chunks):
    """chunks: {(lx, lz): nbt_root}. Zlib (type 2), 4096-byte sectors."""
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for (lx, lz), root in sorted(chunks.items()):
        comp = zlib.compress(nbt_bytes(root))
        blob = struct.pack(">ib", len(comp) + 1, 2) + comp
        pad = (-len(blob)) % 4096
        blob += b"\x00" * pad
        n = len(blob) // 4096
        idx = lx + lz * 32
        struct.pack_into(">I", header, idx * 4, (sector << 8) | n)
        struct.pack_into(">I", header, 4096 + idx * 4, 1)
        body += blob
        sector += n
    with open(path, "wb") as f:
        f.write(header)
        f.write(body)


def read_region(path):
    """Yield chunk NBT roots from one .mca."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    for idx in range(1024):
        (loc,) = struct.unpack_from(">I", data, idx * 4)
        if loc == 0:
            continue
        off = (loc >> 8) * 4096
        length, ctype = struct.unpack_from(">ib", data, off)
        raw = data[off + 5:off + 4 + length]
        if ctype & 0x80:
            raise ValueError("external .mcc chunk not supported: %s idx %d" % (path, idx))
        if ctype == 2:
            raw = zlib.decompress(raw)
        elif ctype == 1:
            import gzip
            raw = gzip.decompress(raw)
        elif ctype != 3:
            raise ValueError("compression %d unsupported" % ctype)
        yield nbt_parse(raw)


# ---- palette packing ----------------------------------------------------

def disc_bits(n):
    if n <= 1:
        return 0
    for limit, bits in ((16, 4), (32, 5), (64, 6), (128, 7), (256, 8)):
        if n <= limit:
            return bits
    return math.ceil(math.log2(n))


def bucket(n):
    return {0: 0, 4: 1, 5: 2, 6: 3, 7: 4, 8: 5}.get(disc_bits(n), 6)


def to_signed(v):
    return v - (1 << 64) if v >= (1 << 63) else v


def pack(ids, bits):
    per = 64 // bits
    longs = [0] * ((4096 + per - 1) // per)
    for i, v in enumerate(ids):
        longs[i // per] |= v << ((i % per) * bits)
    return [to_signed(x) for x in longs]


def unpack(longs, bits, n):
    per = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for i in range(4096):
        li = i // per
        if li >= len(longs):
            raise ValueError("truncated data")
        v = ((longs[li] & 0xFFFFFFFFFFFFFFFF) >> ((i % per) * bits)) & mask
        if v >= n:
            raise ValueError("palette id out of range")
        out.append(v)
    return out


# ---- synthetic terrain --------------------------------------------------

WOOLS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
         "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
SIDES = ["none", "side", "up"]


def wire_state(k):
    e, n, s, w = SIDES[k % 3], SIDES[(k // 3) % 3], SIDES[(k // 9) % 3], SIDES[(k // 27) % 3]
    return ("minecraft:redstone_wire",
            (("east", e), ("north", n), ("power", str((k // 81) % 16)), ("south", s), ("west", w)))


def surface(cx, cz, wx, wz):
    if (cx, cz) == (2, 2):
        return 150 + (wx * 5 + wz * 3) % 40  # mountain: surface in sections 9-11
    return 60 + ((wx * 3 + wz * 5) % 9)  # 60..68, some columns under sea level 62


def block_at(cx, cz, wx, y, wz):
    s = surface(cx, cz, wx, wz)
    if y == -64:
        return ("minecraft:bedrock", ())
    if y < 0:
        if (cx, cz) == (0, 0) and -48 <= y < -32:
            return ("minecraft:deepslate", (("axis", "y"),))  # one single-value non-air section
        if (wx + y + wz) % 29 == 0:
            return ("minecraft:deepslate_diamond_ore", ())
        return ("minecraft:deepslate", (("axis", "y"),))
    if y < s - 4:
        if (cx, cz) == (-1, 0) and 16 <= y < 24 and 4 <= (wx & 15) < 12:
            return ("minecraft:cave_air", ())
        if (wx * 7 + y * 11 + wz * 13) % 37 == 0:
            return ("minecraft:coal_ore", ())
        if (wx + wz) % 17 == 0:
            return ("minecraft:andesite", ())
        return ("minecraft:stone", ())
    if y < s:
        return ("minecraft:dirt", ())
    if y == s:
        if s < 62:
            return ("minecraft:sand", ())
        return ("minecraft:grass_block", (("snowy", "false"),))
    if y <= 62:
        return ("minecraft:water", (("level", "0"),))
    if (cx, cz) == (0, 0) and 96 <= y < 112 and (wx & 15) < 2 and (wz & 15) < 12:
        k = ((y - 96) * 24 + (wz & 15) * 2 + (wx & 15)) % 24
        if k < 16:
            return ("minecraft:%s_wool" % WOOLS[k], ())
        return ("minecraft:%s_concrete" % WOOLS[k - 16], ())
    if (cx, cz) == (-2, -2) and 200 <= y < 216:
        return wire_state(((y - 200) * 256 + (wz & 15) * 16 + (wx & 15)) % 300)
    if y == s + 1 and (wx * 31 + wz * 17) % 11 == 0:
        return ("minecraft:poppy", ())
    return ("minecraft:air", ())


def palette_entry(state):
    name, props = state
    e = {"Name": name}
    if props:
        e["Properties"] = {k: v for k, v in props}
    return e


def synth_chunk(cx, cz):
    # Light-only tags one outside the build range (vanilla writes them, design 0.2).
    sections = [{"Y": Typed(TAG_BYTE, MIN_SECTION_Y - 1)}]
    for si in range(SECTIONS):
        sy = MIN_SECTION_Y + si
        ids = {}
        palette = []
        raw = []
        for i in range(4096):
            lx, lz, ly = i & 15, (i >> 4) & 15, (i >> 8) & 15
            st = block_at(cx, cz, cx * 16 + lx, (sy << 4) + ly, cz * 16 + lz)
            if st not in ids:
                ids[st] = len(palette)
                palette.append(st)
            raw.append(ids[st])
        bs = {"palette": [palette_entry(p) for p in palette]}
        bits = disc_bits(len(palette))
        if bits:
            bs["data"] = Typed(TAG_LONG_ARRAY, pack(raw, bits))
        sections.append({"Y": Typed(TAG_BYTE, sy), "block_states": bs,
                         "biomes": {"palette": [Typed(TAG_STRING, "minecraft:plains")]}})
    sections.append({"Y": Typed(TAG_BYTE, MIN_SECTION_Y + SECTIONS)})
    return {
        "DataVersion": 4903,
        "xPos": cx, "zPos": cz, "yPos": MIN_SECTION_Y,
        "Status": "minecraft:full",
        "LastUpdate": Typed(TAG_LONG, 0), "InhabitedTime": Typed(TAG_LONG, 0),
        "sections": sections,
        "block_entities": [],
        "block_ticks": [],
        "fluid_ticks": [],
        "structures": {},
    }


def cmd_synth(args):
    ccx, ccz = (int(v) for v in args.center.split(","))
    region_dir = os.path.join(args.out, "region")
    os.makedirs(region_dir, exist_ok=True)
    by_region = {}
    for cx in range(ccx - args.half, ccx + args.half + 1):
        for cz in range(ccz - args.half, ccz + args.half + 1):
            by_region.setdefault((cx >> 5, cz >> 5), {})[(cx & 31, cz & 31)] = synth_chunk(cx, cz)
    for (rx, rz), chunks in by_region.items():
        write_region(os.path.join(region_dir, "r.%d.%d.mca" % (rx, rz)), chunks)
    side = 2 * args.half + 1
    meta = {"kind": "g2-synthetic", "center": [ccx, ccz], "half": args.half,
            "chunks": side * side, "regions": len(by_region)}
    with open(os.path.join(args.out, "synthetic.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print("synth %s chunks=%d regions=%d" % (args.out, side * side, len(by_region)))


# ---- census -------------------------------------------------------------

def census(world, square=None):
    c = dict(regions=0, chunks=0, chunksSkipped=0, chunksNotFull=0, tags=0, tagsLightOnly=0,
             tagsNoStates=0, sectionsDecoded=0, airOnly=0, singleNonAir=0, nonAirCells=0)
    buckets = [0] * len(BUCKETS)
    region_dir = os.path.join(world, "region")
    for name in sorted(os.listdir(region_dir)):
        if not name.endswith(".mca"):
            continue
        c["regions"] += 1
        for root in read_region(os.path.join(region_dir, name)):
            x, z = root["xPos"], root["zPos"]
            if square and (abs(x - square[0]) > square[2] or abs(z - square[1]) > square[2]):
                c["chunksSkipped"] += 1
                continue
            c["chunks"] += 1
            if root.get("Status") != "minecraft:full":
                c["chunksNotFull"] += 1
            for sec in root.get("sections", []):
                c["tags"] += 1
                y = sec.get("Y", 0)
                if y < MIN_SECTION_Y or y > MIN_SECTION_Y + SECTIONS - 1:
                    c["tagsLightOnly"] += 1
                    continue
                bs = sec.get("block_states")
                if not isinstance(bs, dict):
                    c["tagsNoStates"] += 1
                    continue
                pal = [p["Name"] for p in bs["palette"]]
                c["sectionsDecoded"] += 1
                buckets[bucket(len(pal))] += 1
                if len(pal) == 1:
                    if pal[0] == "minecraft:air":
                        c["airOnly"] += 1
                    else:
                        c["singleNonAir"] += 1
                    if pal[0] not in AIRS:
                        c["nonAirCells"] += 4096
                    continue
                ids = unpack(bs["data"], disc_bits(len(pal)), len(pal))
                solid = [p not in AIRS for p in pal]
                c["nonAirCells"] += sum(1 for v in ids if solid[v])
    order = ["regions", "chunks", "chunksSkipped", "chunksNotFull", "tags", "tagsLightOnly",
             "tagsNoStates", "sectionsDecoded", "airOnly", "singleNonAir"]
    line = "census " + " ".join("%s=%d" % (k, c[k]) for k in order)
    line += " palette=" + ",".join("%s:%d" % (b, n) for b, n in zip(BUCKETS, buckets))
    line += " nonAirCells=%d" % c["nonAirCells"]
    return line


def cmd_census(args):
    sq = tuple(int(v) for v in args.square.split(",")) if args.square else None
    print(census(args.world, sq))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("synth")
    s.add_argument("out")
    s.add_argument("--half", type=int, default=2)
    s.add_argument("--center", default="0,0")
    s.set_defaults(fn=cmd_synth)
    c = sub.add_parser("census")
    c.add_argument("world")
    c.add_argument("--square", default="")
    c.set_defaults(fn=cmd_census)
    args = ap.parse_args()
    args.fn(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())

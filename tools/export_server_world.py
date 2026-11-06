#!/usr/bin/env python3
"""Export saved block coordinates for LockOn server-index tracers.

Run OFFLINE on a Minecraft server world that you administer, with the server
stopped or a consistent backup. Only reads existing Anvil .mca region files.
No networking, no chunk generation, no world modification, and no extra pip
dependencies. This is a SNAPSHOT, not a live tracker.
"""
from __future__ import annotations

import argparse
import gzip
import math
import re
import struct
import sys
import zlib
from pathlib import Path
from typing import Any


class Reader:
    def __init__(self, buf: bytes) -> None:
        self.data = memoryview(buf)
        self.cursor = 0

    def take(self, count: int) -> memoryview:
        if count < 0 or self.cursor + count > len(self.data):
            raise ValueError("truncated or invalid NBT")
        chunk = self.data[self.cursor:self.cursor + count]
        self.cursor += count
        return chunk

    def number(self, fmt: str) -> Any:
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def string(self) -> str:
        size = self.number(">H")
        return bytes(self.take(size)).decode("utf-8", "replace")

    def payload(self, tag: int) -> Any:
        if tag == 0:
            return None
        if tag == 1:
            return self.number(">b")
        if tag == 2:
            return self.number(">h")
        if tag == 3:
            return self.number(">i")
        if tag == 4:
            return self.number(">q")
        if tag == 5:
            return self.number(">f")
        if tag == 6:
            return self.number(">d")
        if tag == 7:
            count = self.number(">i")
            return bytes(self.take(count))
        if tag == 8:
            return self.string()
        if tag == 9:
            element_tag = self.number(">B")
            count = self.number(">i")
            if count < 0 or count > 10_000_000:
                raise ValueError("invalid NBT list size")
            return [self.payload(element_tag) for _ in range(count)]
        if tag == 10:
            result = {}
            while True:
                child_tag = self.number(">B")
                if child_tag == 0:
                    break
                result[self.string()] = self.payload(child_tag)
            return result
        if tag in (11, 12):
            count = self.number(">i")
            unit = 4 if tag == 11 else 8
            if count < 0 or count > 10_000_000:
                raise ValueError("invalid NBT array size")
            data = self.take(count * unit)
            return list(struct.unpack(">" + ("i" if tag == 11 else "q") * count, data)) if count else []
        raise ValueError(f"unknown NBT tag {tag}")


def parse_nbt(buf: bytes) -> dict[str, Any]:
    nbt = Reader(buf)
    tag = nbt.number(">B")
    if tag != 10:
        raise ValueError("root NBT tag is not a compound")
    nbt.string()  # unnamed / named root
    return nbt.payload(10)


def saved_dimensions(root: Path):
    if (root / "region").is_dir():
        yield "minecraft:overworld", root / "region"
    if (root / "DIM-1" / "region").is_dir():
        yield "minecraft:the_nether", root / "DIM-1" / "region"
    if (root / "DIM1" / "region").is_dir():
        yield "minecraft:the_end", root / "DIM1" / "region"

    dims = root / "dimensions"
    if dims.is_dir():
        for directory in dims.glob("*/*/region"):
            if directory.is_dir():
                rel = directory.relative_to(dims)
                yield f"{rel.parts[0]}:{rel.parts[1]}", directory


def each_region_chunk(path: Path):
    with path.open("rb") as handle:
        header = handle.read(4096)
        if len(header) < 4096:
            return
        for slot in range(1024):
            value = struct.unpack_from(">I", header, slot * 4)[0]
            offset, sector_count = value >> 8, value & 255
            if not offset or not sector_count:
                continue
            try:
                handle.seek(offset * 4096)
                length_bytes = handle.read(4)
                if len(length_bytes) != 4:
                    continue
                length = struct.unpack(">I", length_bytes)[0]
                if length < 2 or length > 32_000_000:
                    continue
                kind = handle.read(1)[0]
                compressed = handle.read(length - 1)
                if kind == 1:
                    uncompressed = gzip.decompress(compressed)
                elif kind == 2:
                    uncompressed = zlib.decompress(compressed)
                elif kind == 3:
                    uncompressed = compressed
                else:
                    # Some Anvil servers use LZ4 (compression=4), which
                    # requires an extra library; skip rather than guessing.
                    continue
                if len(uncompressed) > 64_000_000:
                    continue
                yield parse_nbt(uncompressed)
            except (ValueError, IndexError, OSError, zlib.error):
                continue


def packed_palette_index(data: list[int], number: int, bits: int) -> int:
    values_per_word = 64 // bits
    word = number // values_per_word
    if word >= len(data):
        return -1
    bit = (number % values_per_word) * bits
    return ((data[word] & ((1 << 64) - 1)) >> bit) & ((1 << bits) - 1)


def category_for(block_id: str, extra: set[str]) -> str | None:
    name = block_id.split(":")[-1]
    if "chest" in name:
        return "chest"
    if name.endswith("_bed"):
        return "bed"
    if block_id in extra:
        return "block"
    return None


def markers_in_chunk(chunk: dict[str, Any], dim: str, extra: set[str]):
    world_chunk = chunk.get("Level", chunk)
    cx = world_chunk.get("xPos")
    cz = world_chunk.get("zPos")
    if not isinstance(cx, int) or not isinstance(cz, int):
        return

    sections = world_chunk.get("sections", world_chunk.get("Sections", []))
    for section in sections:
        if not isinstance(section, dict):
            continue
        sy = section.get("Y")
        states = section.get("block_states", {})
        palette = states.get("palette", section.get("Palette", []))
        packed = states.get("data", section.get("BlockStates", []))
        if not isinstance(sy, int) or not palette:
            continue
        matching: dict[int, tuple[str, str]] = {}
        for i, state in enumerate(palette):
            if not isinstance(state, dict):
                continue
            block_id = state.get("Name", "")
            cat = category_for(block_id, extra)
            if cat is None:
                continue
            if cat == "bed" and state.get("Properties", {}).get("part") == "foot":
                continue
            matching[i] = cat, block_id
        if not matching:
            continue

        bits = max(4, math.ceil(math.log2(len(palette))))
        for local_y in range(16):
            for local_z in range(16):
                for local_x in range(16):
                    n = local_y * 256 + local_z * 16 + local_x
                    idx = packed_palette_index(packed, n, bits) if packed else 0
                    if idx not in matching:
                        continue
                    category, block_id = matching[idx]
                    x, y, z = cx * 16 + local_x, sy * 16 + local_y, cz * 16 + local_z
                    yield (dim, category, x + 0.5, y + 0.5, z + 0.5, block_id)


def main() -> int:
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("world", type=Path, help="server world directory containing region/")
    cli.add_argument("--output", type=Path, default=Path("lockon-server-index.tsv"))
    cli.add_argument("--block", action="append", default=[],
                     help="extra block ID to export (repeatable), e.g. minecraft:diamond_ore")
    cli.add_argument("--limit", type=int, default=200_000,
                     help="maximum total exported markers (default 200000)")
    args = cli.parse_args()

    if not args.world.is_dir():
        cli.error("world path does not exist")
    custom = {x if ":" in x else "minecraft:" + x for x in args.block}
    limit = min(200_000, max(1, args.limit))
    count = 0
    regions = 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as out:
        out.write("# LockOn v2.4.0 administrator-exported snapshot — not live player data\n")
        out.write("# dimension<TAB>type<TAB>x<TAB>y<TAB>z<TAB>block_id\n")
        for dimension, directory in saved_dimensions(args.world):
            for region in sorted(directory.glob("r.*.*.mca")):
                regions += 1
                for chunk in each_region_chunk(region):
                    for marker in markers_in_chunk(chunk, dimension, custom):
                        out.write("\t".join(map(str, marker)) + "\n")
                        count += 1
                        if count >= limit:
                            print(f"LIMIT REACHED ({limit}); output is incomplete", file=sys.stderr)
                            print(f"Exported {count} markers to {args.output}")
                            return 0
    print(f"Exported {count} markers across {regions} region files to {args.output}")
    print("Copy the TSV into your Fabric instance's config/ folder and enable the admin index in O → Tracers.")
    print("Export only data from a server world that you are authorized to inspect.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

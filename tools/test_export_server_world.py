#!/usr/bin/env python3
"""Smoke tests for the server world marker exporter; no real world required."""
import tempfile
from pathlib import Path

from export_server_world import category_for, markers_in_chunk, saved_dimensions


def main() -> None:
    assert category_for("minecraft:chest", set()) == "chest"
    assert category_for("minecraft:red_bed", set()) == "bed"
    assert category_for("minecraft:diamond_ore", {"minecraft:diamond_ore"}) == "block"
    assert category_for("minecraft:stone", set()) is None

    # A palette-only Anvil section in which every block is a chest.
    chunk = {"xPos": 12, "zPos": -7, "sections": [
        {"Y": -1, "block_states": {"palette": [{"Name": "minecraft:chest"}]}}
    ]}
    results = list(markers_in_chunk(chunk, "minecraft:overworld", set()))
    assert len(results) == 4096
    assert results[0] == ("minecraft:overworld", "chest", 192.5, -15.5, -111.5)

    # A compact palette where a single bed-head block appears at index 1.
    chunk = {"xPos": 0, "zPos": 0, "sections": [
        {"Y": 0, "block_states": {
            "palette": [
                {"Name": "minecraft:stone"},
                {"Name": "minecraft:blue_bed", "Properties": {"part": "head"}},
            ],
            "data": [16],  # 4-bit packing: block 1 -> palette index 1.
        }}
    ]}
    results = list(markers_in_chunk(chunk, "minecraft:overworld", set()))
    assert results == [("minecraft:overworld", "bed", 1.5, 0.5, 0.5)]

    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        (root / "region").mkdir()
        (root / "DIM-1" / "region").mkdir(parents=True)
        dimensions = dict(saved_dimensions(root))
        assert dimensions.keys() == {"minecraft:overworld", "minecraft:the_nether"}

    print("World index exporter: all tests passed")


if __name__ == "__main__":
    main()

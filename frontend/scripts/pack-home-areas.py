import json
import math
import re
import sys
from pathlib import Path


TILE_DEGREES = 0.25
COORDINATE_SCALE = 100000


def label_of(feature):
    return re.sub(r"(시)(?=[가-힣]+구(?: |$))", r"\1 ", feature["properties"]["label"])


def polygons_of(feature):
    geometry = feature["geometry"]
    polygons = geometry["coordinates"]
    return [polygons] if geometry["type"] == "Polygon" else polygons


def deltas_of(ring):
    deltas = []
    previous_x = 0
    previous_y = 0
    for longitude, latitude in ring[:-1]:
        x = round(longitude * COORDINATE_SCALE)
        y = round(latitude * COORDINATE_SCALE)
        deltas.extend([x - previous_x, y - previous_y])
        previous_x = x
        previous_y = y
    return deltas


def tile_names_of(polygon):
    longitudes, latitudes = zip(*polygon[0])
    return [
        f"{column}_{row}"
        for column in range(math.floor(min(longitudes) / TILE_DEGREES), math.floor(max(longitudes) / TILE_DEGREES) + 1)
        for row in range(math.floor(min(latitudes) / TILE_DEGREES), math.floor(max(latitudes) / TILE_DEGREES) + 1)
    ]


def main(output_directory):
    tiles = {}
    for feature in json.load(sys.stdin)["features"]:
        label = label_of(feature)
        for polygon in polygons_of(feature):
            encoded = [deltas_of(ring) for ring in polygon]
            for name in tile_names_of(polygon):
                tiles.setdefault(name, {}).setdefault(label, []).append(encoded)

    output_directory.mkdir(parents=True, exist_ok=True)
    for stale in output_directory.glob("*.json"):
        stale.unlink()
    for name, areas in tiles.items():
        tile = {"scale": COORDINATE_SCALE, "areas": [[label, polygons] for label, polygons in areas.items()]}
        text = json.dumps(tile, ensure_ascii=False, separators=(",", ":"))
        (output_directory / f"{name}.json").write_text(text, encoding="utf-8")


if len(sys.argv) != 2:
    sys.exit("사용법: pack-home-areas.py <타일 폴더> < 경계.geojson")
main(Path(sys.argv[1]))

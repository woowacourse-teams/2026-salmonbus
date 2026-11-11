import json
import re
import sys


features = json.load(sys.stdin)["features"]
print("[")
for index, feature in enumerate(features):
    label = re.sub(r"(시)(?=[가-힣]+구(?: |$))", r"\1 ", feature["properties"]["label"])
    geometry = feature["geometry"]
    polygons = geometry["coordinates"]
    if geometry["type"] == "Polygon":
        polygons = [polygons]
    coordinates = [point for polygon in polygons for ring in polygon for point in ring]
    x, y = zip(*coordinates)
    area = {
        "label": label,
        "west": min(x),
        "south": min(y),
        "east": max(x),
        "north": max(y),
        "polygons": polygons,
    }
    suffix = "," if index + 1 < len(features) else ""
    print(f"  {json.dumps(area, ensure_ascii=False, separators=(',', ':'))}{suffix}")
print("]")

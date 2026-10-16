#!/usr/bin/env python3
"""Build the dev road-shape replay from public static GBIS data, without credentials."""

import argparse
import concurrent.futures
import hashlib
import json
import math
import pathlib
import sys
import urllib.request
from datetime import datetime, timezone


REPO = pathlib.Path(__file__).resolve().parents[3]
SOURCE_URL = "https://www.gbis.go.kr/gbis2014/schBusAPI.action?cmd=searchMapLineJson&routeId={}"
SCRIPT_URL = "https://www.gbis.go.kr/gbis2014/js/map/searchTotal_2025.js?ver=1.17"
CAPTURE = REPO / "backend/api-app/src/test/resources/gbis/route-geometry-eight-20261014.json"
EARTH_RADIUS = 6_378_137.0


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_catalog():
    routes = []
    for name in ("routes.json", "catalog-routes.json"):
        routes.extend(json.loads((REPO / "dev/data" / name).read_text())["routes"])
    ids = [route["routeId"] for route in routes]
    if len(ids) != len(set(ids)):
        raise ValueError("The two catalog fixtures must have unique route IDs")
    for route in routes:
        if len(route["routeId"]) != 9 or not route["routeId"].isascii() or not route["routeId"].isdecimal():
            raise ValueError("Catalog route IDs must contain nine ASCII digits")
        digest = route["contentDigest"]
        if len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest):
            raise ValueError("Catalog reference digests must contain 64 lowercase hexadecimal digits")
    return routes


def check_point(point):
    x, y = point["x"], point["y"]
    if not (math.isfinite(x) and math.isfinite(y) and -180 <= x <= 180 and -90 <= y <= 90
            and x != 0 and y != 0):
        raise ValueError("Invalid longitude/latitude")


def unproject(point):
    # EPSG:3857 (the official page's map projection) -> EPSG:4326.
    x = float(point["lon"]) / EARTH_RADIUS
    y = float(point["lat"]) / EARTH_RADIUS
    result = {"x": round(math.degrees(x), 9),
              "y": round(math.degrees(2 * math.atan(math.exp(y)) - math.pi / 2), 9)}
    check_point(result)
    return result


def capture_missing(route_id, raw_dir):
    raw_path = raw_dir / (route_id + ".json")
    metadata_path = raw_dir / (route_id + ".metadata.json")
    if raw_path.exists() and metadata_path.exists():
        return
    if raw_path.exists() or metadata_path.exists():
        raise ValueError(f"Incomplete existing capture for {route_id}; inspect it before retrying")
    url = SOURCE_URL.format(route_id)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
        if response.status != 200:
            raise ValueError(f"Unexpected HTTP status for {route_id}")
    decoded = json.loads(data)
    if decoded.get("success") is not True:
        raise ValueError(f"Public static shape was unavailable for {route_id}")
    metadata = {"url": url, "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
                "responseSha256": sha256(data), "responseBytes": len(data)}
    raw_path.write_bytes(data)
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")


def public_shape(route_id, raw_dir):
    raw = (raw_dir / (route_id + ".json")).read_bytes()
    metadata = json.loads((raw_dir / (route_id + ".metadata.json")).read_text())
    if metadata["responseSha256"] != sha256(raw):
        raise ValueError(f"Capture hash mismatch for {route_id}")
    data = json.loads(raw)
    if data.get("success") is not True or set(data["result"]["gg"]) != {"upLine", "downLine"}:
        raise ValueError(f"Unexpected public static shape response for {route_id}")
    points = []
    direction_counts = {}
    for key in ("upLine", "downLine"):
        direction = data["result"]["gg"][key]
        rows = direction["list"]
        sequence = [int(row["seq"]) for row in rows]
        if direction["count"] != len(rows) or sequence != sorted(set(sequence)):
            raise ValueError(f"Invalid {key} count/order for {route_id}")
        # Match getRouteLineSuccess in GBIS's official renderer. Type 2 points
        # are not drawn there; this intentionally does not infer their meaning.
        visible = [row for row in rows if str(row["type"]) != "2"]
        if len(visible) < 2:
            raise ValueError(f"Missing {key} public road path for {route_id}")
        direction_counts[key] = {"rawPointCount": len(rows), "pointCount": len(visible),
                                 "excludedType2Count": len(rows) - len(visible)}
        points.extend(unproject(row) for row in visible)
    return points, {"kind": "GBIS_PUBLIC_WEB_STATIC", **metadata,
                    "sourceCoordinateSystem": "EPSG:3857", "coordinateSystem": "EPSG:4326",
                    "transform": "inverse_spherical_mercator_R_6378137_round_9dp",
                    "pointSelection": "official_renderer_type_not_2",
                    "pointOrder": "upLine_then_downLine_preserving_each_response_order",
                    "directions": direction_counts}


def distance(a, b):
    xscale = 111_195.08 * math.cos(math.radians((a["y"] + b["y"]) / 2))
    return math.hypot((a["x"] - b["x"]) * xscale, (a["y"] - b["y"]) * 111_195.08)


def stop_offsets(catalog_route, points):
    origin = points[0]
    xscale = 111_195.08 * math.cos(math.radians(origin["y"]))
    xy = [((point["x"] - origin["x"]) * xscale,
           (point["y"] - origin["y"]) * 111_195.08) for point in points]
    segments = []
    for a, b in zip(xy, xy[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        segments.append((a[0], a[1], dx, dy, dx * dx + dy * dy))
    result = []
    for stop in catalog_route["stops"]:
        check_point(stop)
        sx, sy = (stop["x"] - origin["x"]) * xscale, (stop["y"] - origin["y"]) * 111_195.08
        nearest = float("inf")
        for ax, ay, dx, dy, squared in segments:
            fraction = 0 if squared == 0 else max(0, min(1, ((sx - ax) * dx + (sy - ay) * dy) / squared))
            nearest = min(nearest, math.hypot(sx - ax - fraction * dx, sy - ay - fraction * dy))
        result.append({"sequence": stop["sequence"], "offsetMeters": round(nearest, 3)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=pathlib.Path, required=True,
                        help="Ignored local directory containing public JSON captures and hash/time metadata")
    parser.add_argument("--capture-missing", action="store_true",
                        help="Make one public static GET for each absent non-retained route (two concurrent requests)")
    parser.add_argument("--output", type=pathlib.Path, default=REPO / "dev/data/route-shapes.json")
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    catalog = read_catalog()
    retained_raw = CAPTURE.read_bytes()
    retained_capture = json.loads(retained_raw)
    retained = {route["routeId"]: route for route in retained_capture["routes"]}
    required = [route["routeId"] for route in catalog if route["routeId"] not in retained]
    if args.capture_missing:
        args.raw_dir.mkdir(parents=True, exist_ok=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(lambda route_id: capture_missing(route_id, args.raw_dir), required))
    shapes, report = [], []
    for route in catalog:
        route_id = route["routeId"]
        if route_id in retained:
            original = retained[route_id]
            sequence = [point["lineSeq"] for point in original["shape"]]
            if sequence != list(range(1, len(sequence) + 1)):
                raise ValueError(f"Invalid retained source order for {route_id}")
            points = [{"x": point["x"], "y": point["y"]} for point in original["shape"]]
            source = {"kind": "GBIS_RETAINED_STATIC_CAPTURE",
                      "capturedAtUtc": retained_capture["capturedAtUtc"],
                      "captureFile": str(CAPTURE.relative_to(REPO)),
                      "captureFileSha256": sha256(retained_raw),
                      "coordinateSystem": retained_capture["coordinateSystem"],
                      "pointOrder": "original_lineSeq"}
        else:
            points, source = public_shape(route_id, args.raw_dir)
        if len(points) < 2 or len({(p["x"], p["y"]) for p in points}) < 2:
            raise ValueError(f"Empty/degenerate source shape for {route_id}")
        for point in points:
            check_point(point)
        shape = {"routeId": route_id, "referenceDigest": route["contentDigest"],
                 "source": source, "points": points}
        shapes.append(shape)
        offsets = stop_offsets(route, points)
        row = {"routeId": route_id, "displayName": route["displayName"], "pointCount": len(points),
               "stopCount": len(offsets), "sourceKind": source["kind"],
               "maxStopOffsetMeters": max(stop["offsetMeters"] for stop in offsets),
               "stopsOver100Meters": [stop for stop in offsets if stop["offsetMeters"] > 100],
               "maxConsecutivePointGapMeters": round(max(distance(a, b) for a, b in zip(points, points[1:])), 3),
               "consecutiveDuplicatePoints": sum(a == b for a, b in zip(points, points[1:]))}
        if "directions" in source:
            boundary = source["directions"]["upLine"]["pointCount"]
            row["directionBoundaryGapMeters"] = round(distance(points[boundary - 1], points[boundary]), 3)
        report.append(row)
    output = {"version": "gbis-route-shapes-v1",
              "source": {"description": "Public static GBIS road geometry for isolated dev replay; no vehicle/authentication data",
                         "officialMapPage": "https://www.gbis.go.kr/gbis2014/schBus.action",
                         "officialMapScript": SCRIPT_URL,
                         "projectionEvidence": "searchTotal_2025.js lines 38-41,129; EPSG:3857 map and EPSG:4326 WGS84",
                         "pointSelectionEvidence": "getRouteLineSuccess lines 2999-3056; ignore type 2, preserve direction order",
                         "referenceBinding": "Shipped catalog contentDigest; upstream has no matching reference-version identifier"},
              "routes": shapes}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n")
    result = {"routeCount": len(shapes), "stopCount": sum(row["stopCount"] for row in report),
              "pointCount": sum(row["pointCount"] for row in report), "routes": report}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("routeCount", "stopCount", "pointCount")}))
    outliers = [row for row in report if row["stopsOver100Meters"]]
    if outliers:
        print(json.dumps({"routesWithStopsOver100Meters": outliers}, ensure_ascii=False), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

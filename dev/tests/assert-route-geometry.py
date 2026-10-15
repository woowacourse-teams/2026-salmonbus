#!/usr/bin/env python3
"""Check the seeded catalog against real HTTP geometry responses in an isolated local stack."""
import argparse
import json
import math
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


def request(url, headers=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=15) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as response:
        return response.code, dict(response.headers), response.read()


def point_valid(point):
    return (isinstance(point, dict) and all(isinstance(point.get(key), (int, float))
            and math.isfinite(point[key]) and point[key] != 0 for key in ("x", "y"))
            and -180 <= point["x"] <= 180 and -90 <= point["y"] <= 90)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--check-docs", action="store_true")
    args = parser.parse_args()
    # This check must never become an accidental public dev load test.
    assert urllib.parse.urlparse(args.api).hostname in ("127.0.0.1", "localhost"), "Local API only"
    routes = [route for name in ("routes.json", "catalog-routes.json")
              for route in json.loads((args.data / name).read_text())["routes"]]
    shapes = {route["routeId"]: route for route in json.loads((args.data / "route-shapes.json").read_text())["routes"]}
    expected = {route["routeId"]: route for route in routes}
    assert len(expected) == len(routes) and set(shapes) == set(expected), "Shape and catalog coverage differ"
    status, _, raw = request(args.api + "/api/v1/routes")
    assert status == 200, f"Route list HTTP {status}"
    listed = json.loads(raw)["routes"]
    assert {route["id"] for route in listed} == set(expected), "Public route list differs from seed"
    if args.check_docs:
        status, _, raw = request(args.api + "/api/docs/openapi")
        assert status == 200, f"OpenAPI HTTP {status}"
        document = json.loads(raw)
        operation = document["paths"]["/api/v2/routes/{routeId}/geometry"]["get"]
        assert operation["operationId"] == "getRouteGeometry"
        assert "41" in operation["description"] and "35,542" in operation["description"]
        examples = operation["responses"]["200"]["content"]["application/json"]["examples"]
        assert set(examples) == {"ready", "partial", "notCollected", "withoutDirectionSplit"}
        assert examples["ready"]["value"]["status"] == "READY"
        assert examples["partial"]["value"]["status"] == "PARTIAL"
        assert examples["notCollected"]["value"]["points"] == []
        assert examples["withoutDirectionSplit"]["value"]["paths"] is None
        assert {"304", "400", "404", "409", "503"} <= operation["responses"].keys()
        status, _, config = request(args.api + "/api/docs/openapi/swagger-config")
        assert status == 200 and json.loads(config)["supportedSubmitMethods"] == []
        args.output.parent.mkdir(parents=True, exist_ok=True)
        (args.output.parent / "openapi.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n")
    responses = []
    rows = []
    deadline = time.monotonic() + 120
    for route in routes:
        route_id = route["routeId"]
        url = args.api + f"/api/v2/routes/{route_id}/geometry"
        while True:
            status, headers, raw = request(url)
            assert status == 200, f"{route_id}: geometry HTTP {status}"
            body = json.loads(raw)
            if body["status"] != "UNAVAILABLE" or time.monotonic() >= deadline:
                break
            time.sleep(.5)
        assert body["status"] == "READY" and body["reason"] is None, (route_id, body["status"], body["reason"])
        assert body["routeId"] == route_id and body["name"] == route["displayName"], route_id
        assert body["startPoint"] == route["startStopName"] and body["endPoint"] == route["endStopName"], route_id
        assert body["coordinateSystem"] == "WGS84_ASSUMED", route_id
        assert body["turningPoint"]["stopSequence"] == route["turnSequence"], route_id
        assert len(body["busStops"]) == len(route["stops"]), route_id
        for actual, stop in zip(body["busStops"], route["stops"]):
            assert actual == {"stationId": stop["stationId"], "stopSequence": stop["sequence"],
                              "name": stop["name"], "direction": stop["direction"],
                              "boardingAllowed": stop["boardingAllowed"],
                              "point": {"x": stop["x"], "y": stop["y"]}}, (route_id, stop["sequence"])
            assert point_valid(actual["point"]), (route_id, stop["sequence"])
        assert shapes[route_id]["referenceDigest"] == route["contentDigest"], route_id
        assert body["points"] == shapes[route_id]["points"], f"{route_id}: source shape was changed"
        assert len(body["points"]) >= 2 and len({(p["x"], p["y"]) for p in body["points"]}) >= 2, route_id
        assert all(point_valid(point) for point in body["points"]), route_id
        boundary = body["boundary"]
        for point in body["points"] + [stop["point"] for stop in body["busStops"]]:
            assert boundary["left"] <= point["x"] <= boundary["right"], route_id
            assert boundary["bottom"] <= point["y"] <= boundary["top"], route_id
        paths = body["paths"]
        if paths is not None:
            assert set(paths) == {"UP", "DOWN"}, route_id
            up, down = paths["UP"]["points"], paths["DOWN"]["points"]
            assert len(up) >= 2 and len(down) >= 2 and all(point_valid(p) for p in up + down), route_id
            assert up[0] == body["points"][0] and down[-1] == body["points"][-1] and up[-1] == down[0], route_id
            # A split inserts zero, one or two copies of its shared boundary.
            # A zero-length source edge already contains two identical original
            # points, so unconditionally dropping down[0] would lose an original.
            # Removing only possible inserted boundary copies must recover the
            # exact full source array, including every duplicate, in its order.
            assert any((up[:-1] if drop_up else up) + (down[1:] if drop_down else down) == body["points"]
                       for drop_up in (False, True) for drop_down in (False, True)), \
                f"{route_id}: directional paths lost, inserted or reordered source points"
        version = body["referenceVersionId"]
        assert isinstance(version, str) and version.isdecimal() and int(version) > 0, route_id
        pinned, _, pinned_raw = request(url + "?referenceVersionId=" + version)
        assert pinned == 200 and json.loads(pinned_raw) == body, route_id
        etag = headers.get("ETag") or headers.get("Etag")
        assert etag and "max-age=300" in headers.get("Cache-Control", ""), route_id
        cached, _, cached_raw = request(url, {"If-None-Match": etag})
        assert cached == 304 and cached_raw == b"", route_id
        stale, _, _ = request(url + "?referenceVersionId=" + str(int(version) + 1_000_000))
        assert stale == 409, route_id
        responses.append(body)
        rows.append({"routeId": route_id, "name": body["name"], "referenceVersionId": version,
                     "stops": len(body["busStops"]), "points": len(body["points"]),
                     "paths": None if paths is None else {key: len(value["points"]) for key, value in paths.items()}})
    if args.compare:
        assert responses == json.loads(args.compare.read_text()), "Geometry changed across restart or upstream failure"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(responses, ensure_ascii=False, indent=2) + "\n")
    summary = {"routes": len(rows), "stops": sum(row["stops"] for row in rows),
               "points": sum(row["points"] for row in rows), "splitRoutes": sum(row["paths"] is not None for row in rows),
               "rows": rows}
    args.output.with_suffix(".summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key != "rows"}))


if __name__ == "__main__":
    main()

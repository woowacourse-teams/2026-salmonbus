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


def display_point_valid(point):
    return (point_valid(point) and set(point) == {"x", "y"}
            and all(type(point[key]) in (int, float) for key in ("x", "y")))


ROOT_FIELDS = {"routeId", "referenceVersionId", "geometryRevision", "name", "startPoint", "endPoint",
               "status", "reason", "coordinateSystem", "turningPoint", "busStops", "points", "paths", "boundary"}
STOP_FIELDS = {"stationId", "stopSequence", "name", "direction", "boardingAllowed", "point"}
DIAGNOSTIC_FIELDS = {"displayPaths", "displaySource", "displayEvidence", "displayQuality", "displayAccuracyGrade"}


def assert_stop_contract(actual, stop, body):
    context = (body["routeId"], stop["sequence"])
    expected = {"stationId": stop["stationId"], "stopSequence": stop["sequence"],
                "name": stop["name"], "direction": stop["direction"],
                "boardingAllowed": stop["boardingAllowed"],
                "point": {"x": stop["x"], "y": stop["y"]}}
    assert isinstance(actual, dict) and set(expected) <= actual.keys() <= \
        set(expected) | {"displayPoint", "pathConnection"}, context
    # Additive display metadata must not change any original visit or GPS field.
    assert {key: actual[key] for key in expected} == expected, context
    assert point_valid(actual["point"]), context
    display_point = actual.get("displayPoint")
    assert display_point is None or display_point_valid(display_point), context
    assert_path_connection(actual, body, context)


def assert_path_connection(actual, body, context):
    connection = actual.get("pathConnection")
    if connection is None:
        return
    assert isinstance(connection, dict) and set(connection) == \
        {"point", "segmentIndex", "segmentFraction"}, context
    assert display_point_valid(connection["point"]), context
    index, fraction = connection["segmentIndex"], connection["segmentFraction"]
    assert type(index) is int and index >= 0, context
    assert type(fraction) in (int, float) and 0 <= fraction <= 1 and math.isfinite(fraction), context
    paths = body.get("displayPaths") if "displayPaths" in body else body.get("paths")
    assert isinstance(paths, dict), context
    path = paths.get(actual["direction"])
    assert isinstance(path, dict) and isinstance(path.get("points"), list), context
    points = path["points"]
    assert index + 1 < len(points), context
    a, b = points[index:index + 2]
    assert display_point_valid(a) and display_point_valid(b), context
    # Match the consumer's tolerance for the serialized final segment interpolation.
    for key in ("x", "y"):
        interpolated = a[key] + (b[key] - a[key]) * fraction
        assert math.isclose(interpolated, connection["point"][key], rel_tol=0, abs_tol=1e-9), context


def assert_document_contract(document):
    operation = document["paths"]["/api/v2/routes/{routeId}/geometry"]["get"]
    assert operation["operationId"] == "getRouteGeometry"
    assert "41" in operation["description"] and "35,542" in operation["description"]
    parameters = {parameter["name"]: parameter for parameter in operation["parameters"]}
    assert parameters["includeDiagnostics"]["in"] == "query"
    assert parameters["includeDiagnostics"]["required"] is False
    assert parameters["includeDiagnostics"]["schema"]["type"] == "boolean"
    assert parameters["includeDiagnostics"]["schema"]["default"] is False

    def referenced_schema(schema):
        if "$ref" in schema:
            assert schema["$ref"].startswith("#/"), schema["$ref"]
            resolved = document
            for part in schema["$ref"][2:].split("/"):
                resolved = resolved[part]
            return referenced_schema(resolved)
        alternatives = schema.get("anyOf", schema.get("oneOf"))
        if alternatives is not None:
            return referenced_schema(next(option for option in alternatives if option.get("type") != "null"))
        return schema

    content = operation["responses"]["200"]["content"]["application/json"]
    geometry_schema = referenced_schema(content["schema"])
    stop_schema = referenced_schema(geometry_schema["properties"]["busStops"]["items"])
    connection_schema = referenced_schema(stop_schema["properties"]["pathConnection"])
    assert set(geometry_schema["required"]) == ROOT_FIELDS
    assert set(stop_schema["required"]) == STOP_FIELDS
    assert set(connection_schema["required"]) == {"point", "segmentIndex", "segmentFraction"}
    assert {"304", "400", "404", "409", "503"} <= operation["responses"].keys()
    examples = operation["responses"]["200"]["content"]["application/json"]["examples"]
    assert set(examples) == {"ready", "partial", "notCollected", "withoutDirectionSplit",
                             "displayWithheld", "diagnostic"}
    values = {key: example["value"] for key, example in examples.items()}
    for name, body in values.items():
        assert set(body) == ROOT_FIELDS | (DIAGNOSTIC_FIELDS if name == "diagnostic" else set()), name
        for stop in body["busStops"]:
            assert STOP_FIELDS <= stop.keys() <= STOP_FIELDS | {"pathConnection"}, name
            assert stop["point"] is None or display_point_valid(stop["point"]), name
            assert_path_connection(stop, body, (name, stop["stopSequence"]))
    ready = values["ready"]
    assert ready["status"] == "READY" and ready["reason"] is None
    assert all(stop.get("pathConnection") is not None for stop in ready["busStops"])
    partial = values["partial"]
    assert partial["status"] == "PARTIAL" and partial["reason"] == "MISSING_STOP_COORDINATES"
    assert any(stop["point"] is None for stop in partial["busStops"])
    assert values["withoutDirectionSplit"]["status"] == "READY"
    assert values["withoutDirectionSplit"]["paths"] is None
    for name, reason in (("notCollected", "NOT_COLLECTED"), ("displayWithheld", "DISPLAY_WITHHELD")):
        unavailable = values[name]
        assert unavailable["status"] == "UNAVAILABLE" and unavailable["reason"] == reason
        assert unavailable["points"] == [] and unavailable["paths"] is None
        assert all(set(stop) == STOP_FIELDS for stop in unavailable["busStops"])
        assert [{key: stop[key] for key in STOP_FIELDS} for stop in unavailable["busStops"]] == \
            [{key: stop[key] for key in STOP_FIELDS} for stop in ready["busStops"]]
    diagnostic = values["diagnostic"]
    assert diagnostic["displayPaths"] is not None and diagnostic["paths"] is not None
    assert diagnostic["displaySource"] == "AUTOMATIC_FALLBACK"
    assert diagnostic["displayAccuracyGrade"] == "UNEVALUATED"
    assert set(diagnostic["displayQuality"]) == {"status", "issues"}
    assert diagnostic["displayQuality"]["status"] == "REVIEW_REQUIRED"
    assert diagnostic["displayQuality"]["issues"]
    assert all(set(issue) == {"code", "direction", "sequence", "meters"}
               for issue in diagnostic["displayQuality"]["issues"])
    assert diagnostic["displayEvidence"]
    assert all(set(evidence) == {"id", "status", "digest"} for evidence in diagnostic["displayEvidence"])
    assert [{key: stop[key] for key in STOP_FIELDS} for stop in diagnostic["busStops"]] == \
        [{key: stop[key] for key in STOP_FIELDS} for stop in ready["busStops"]]


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
        assert_document_contract(document)
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
        assert set(body) == ROOT_FIELDS, route_id
        assert all(STOP_FIELDS <= stop.keys() <= STOP_FIELDS | {"pathConnection"}
                   for stop in body["busStops"]), route_id
        assert body["status"] == "READY" and body["reason"] is None, (route_id, body["status"], body["reason"])
        assert body["routeId"] == route_id and body["name"] == route["displayName"], route_id
        assert body["startPoint"] == route["startStopName"] and body["endPoint"] == route["endStopName"], route_id
        assert body["coordinateSystem"] == "WGS84_ASSUMED", route_id
        assert body["turningPoint"]["stopSequence"] == route["turnSequence"], route_id
        assert len(body["busStops"]) == len(route["stops"]), route_id
        progress = {"UP": -1, "DOWN": -1}
        has_connections = any(stop.get("pathConnection") is not None for stop in body["busStops"])
        for actual, stop in zip(body["busStops"], route["stops"]):
            assert_stop_contract(actual, stop, body)
            if has_connections:
                connection = actual.get("pathConnection")
                assert connection is not None, (route_id, stop["sequence"])
                at = connection["segmentIndex"] + connection["segmentFraction"]
                assert at >= progress[actual["direction"]], (route_id, stop["sequence"])
                progress[actual["direction"]] = at
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
            assert up[-1] == down[0], f"{route_id}: directional seam differs"
            for point in up + down:
                assert boundary["left"] <= point["x"] <= boundary["right"], route_id
                assert boundary["bottom"] <= point["y"] <= boundary["top"], route_id
            # Diagnostic/legacy paths split the exact source; compact paths with
            # connections are the selected generated shape and may resample it.
            if not has_connections:
                assert up[0] == body["points"][0] and down[-1] == body["points"][-1], route_id
                assert any((up[:-1] if drop_up else up) + (down[1:] if drop_down else down) == body["points"]
                           for drop_up in (False, True) for drop_down in (False, True)), \
                    f"{route_id}: source directional paths lost, inserted or reordered source points"
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

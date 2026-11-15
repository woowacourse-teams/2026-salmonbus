#!/usr/bin/env python3
"""Build the local synthetic model bundle (dev/data/model-reference) in the confirmed input profile.

The bundle carries constant trees and uniform coefficients, not a model trained on observations. It exists so
the local worker can run the same forecast and scoring path as a real bundle. Two serving checks need recorded
observations (historical replay, settlement) and cannot be run for it. The tool never writes receipts for
them: the serving manifest lists them under runtime.unverified_checks and marks synthetic_fixture, and only a
loader that explicitly allows synthetic fixtures accepts such a bundle.

Steps (every output directory must be new):

  1. source   inactive source bundle. Model files and statistics values are kept as they are; only the
              confirmed contract fields and the public stop list of each route are added.
       python3 dev/data/tools/build_model_reference.py source --output <source>

  2. run the two checks that need no observations against <source> (from backend/):
       ./gradlew :forecasting:verifyAlightingAwareParity \\
           -PalightingBundle=<source> -PalightingParityOutput=<numeric.json>
       ./gradlew :forecasting:verifyAlightingAwareServingReadiness -PalightingReadinessMode=candidate \\
           -PalightingBundle=<source> -PalightingReadinessOutput=<runtime.json> \\
           -PalightingReleaseId=<release id>

  3. serving  serving bundle with receipts for exactly those two reports.
       python3 dev/data/tools/build_model_reference.py serving --source <source> \\
           --numeric <numeric.json> --runtime <runtime.json> --output <serving>
     Replace dev/data/model-reference with <serving>, then update the file digests and the data version in
     dev/data/java/com/gustler/localdata/ReferenceBundle.java. That file is part of the shared dev data
     contract, so the new contract digest must also be added to the reviewed list of the shared dev tools
     (CompatiblePreparation), and the local volume names in compose.local.yaml follow the data version.

  check       rebuilds the committed bundle from its own model files, routes.json and the reports recorded in
              its evidence files, and fails unless every byte matches.
       python3 dev/data/tools/build_model_reference.py check

Standard library only.
"""

import argparse
import hashlib
import json
import pathlib
import sys
import tempfile
from datetime import datetime, timezone

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "backend/tools/alighting_model"))
from confirmed_serving_verification import CONTRACT, PROFILE, checked_routes, verify_result  # noqa: E402

REFERENCE = REPO / "dev/data/model-reference"
ROUTES = REPO / "dev/data/routes.json"
PERFORMED = ("feature_parity", "runtime_validation")
UNPERFORMED = ("historical_replay", "settlement")
COPIED = ("base.json", "gate.json", "pool.json", "constants.json", "golden.json", "boundaries.json",
          "statistics-manifest.json")
SCOPE = "weekday-rush-6-10-17-21-horizon-1-12"


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def compact(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode()


def indented(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()


def write_new(output, contents):
    if output.exists():
        raise ValueError(f"output must be a new directory: {output}")
    output.mkdir(parents=True)
    for name, content in contents.items():
        (output / name).write_bytes(content)


def read_declared(directory):
    """Read the manifest and every file it declares, rejecting files that no longer match their digest."""
    manifest_bytes = (directory / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    contents = {}
    for name, expected in manifest["files"].items():
        if pathlib.Path(name).name != name or (directory / name).is_symlink():
            raise ValueError(f"invalid bundle file: {name}")
        contents[name] = (directory / name).read_bytes()
        if sha256(contents[name]) != expected:
            raise ValueError(f"changed bundle file: {name}")
    return manifest_bytes, manifest, contents


def route_reference(routes, routes_file):
    catalog = {route["displayName"]: route for route in json.loads(routes_file.read_bytes())["routes"]}
    return dict(routes=[
        dict(modelRoute=route, sourceRouteId=catalog[route]["routeId"], stops=[
            dict(order=stop["sequence"], id=stop["stationId"], boardingAllowed=stop["boardingAllowed"],
                 direction=stop["direction"]) for stop in catalog[route]["stops"]])
        for route in routes])


def build_source(reference, routes_file, output):
    contents = {name: (reference / name).read_bytes() for name in COPIED}
    contract = json.loads((reference / "feature-contract.json").read_bytes())
    contract.update(CONTRACT)
    contents["feature-contract.json"] = indented(contract)
    routes = json.loads(contents["statistics-manifest.json"])["routes"]
    for route in routes:
        statistics = json.loads((reference / f"statistics-{route}.json").read_bytes())
        statistics["input_profile"] = PROFILE
        contents[f"statistics-{route}.json"] = compact(statistics)
    contents["route-reference.json"] = compact(route_reference(routes, routes_file))
    if checked_routes(contents) != routes:
        raise ValueError("source bundle does not follow the confirmed contract")
    manifest = dict(schema="alighting-inference-bundle-v1", model="alighting-gate-base-and-pool",
                    output_targets=["after-alighting-estimated", "departure-zero"], serving_ready=False,
                    synthetic_fixture=True, files={name: sha256(content) for name, content in contents.items()})
    contents["manifest.json"] = compact(manifest)
    write_new(output, contents)
    return sha256(contents["manifest.json"])


def data_through(contents, routes):
    cutoffs = []
    for route in routes:
        statistics = json.loads(contents[f"statistics-{route}.json"])
        for field in ("events_known_through", "observations_through"):
            cutoffs.append(datetime.fromisoformat(statistics[field].replace("Z", "+00:00")))
    if any(cutoff.tzinfo is None for cutoff in cutoffs):
        raise ValueError("statistics cutoff must include a timezone")
    return max(cutoffs).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_serving(source, reports, output):
    """reports maps each performed check to the report file the check itself wrote."""
    source_bytes, manifest, contents = read_declared(source)
    if manifest.get("serving_ready") is not False or manifest.get("synthetic_fixture") is not True:
        raise ValueError("expected the inactive synthetic source bundle")
    routes = checked_routes(contents)
    if routes is None:
        raise ValueError("confirmed source bundle required")
    if set(reports) != set(PERFORMED):
        raise ValueError("reports of exactly the performed checks are required")
    source_digest = sha256(source_bytes)
    results = {}
    for check in PERFORMED:
        result = json.loads(reports[check].read_bytes())
        cases = verify_result(check, result, source_digest, routes)
        evidence = compact(dict(schema="alighting-confirmed-serving-evidence-v1", routes=routes,
                                verified_cases=cases, result=result))
        contents[f"evidence-{check}.json"] = evidence
        contents[f"verification-{check}.json"] = compact(dict(
            schema="alighting-serving-verification-v1", check=check, status="PASS",
            source_manifest_sha256=source_digest, feature_contract_version="alighting-online-v1",
            verified_cases=cases, evidence_file=f"evidence-{check}.json", evidence_sha256=sha256(evidence),
            routes=routes, **CONTRACT))
        results[check] = result
    runtime_report = results["runtime_validation"]
    through = data_through(contents, routes)
    if runtime_report.get("data_through") != through:
        raise ValueError("runtime report was made for different statistics cutoffs")
    contents["verification-source-manifest.json"] = source_bytes
    manifest["serving_ready"] = True
    manifest["runtime"] = dict(release_id=runtime_report["release_id"],
                               feature_contract_version="alighting-online-v1", data_through=through,
                               source_manifest_sha256=source_digest, routes=routes, scope=SCOPE,
                               unverified_checks=list(UNPERFORMED), **CONTRACT)
    manifest["files"] = {name: sha256(content) for name, content in contents.items()}
    contents["manifest.json"] = compact(manifest)
    write_new(output, contents)
    return sha256(contents["manifest.json"])


def rebuild(reference, routes_file, workspace):
    """Rebuild the bundle in workspace from the reference's own inputs and recorded reports."""
    build_source(reference, routes_file, workspace / "source")
    reports = {}
    for check in PERFORMED:
        evidence = json.loads((reference / f"evidence-{check}.json").read_bytes())
        reports[check] = workspace / f"{check}.json"
        reports[check].write_bytes(compact(evidence["result"]))
    build_serving(workspace / "source", reports, workspace / "serving")
    return workspace / "serving"


def differences(reference, routes_file):
    """Names of files that differ between the reference and a rebuild of it. Empty when reproducible."""
    with tempfile.TemporaryDirectory() as workspace:
        rebuilt = rebuild(reference, routes_file, pathlib.Path(workspace))
        expected = {path.name: path.read_bytes() for path in reference.iterdir()}
        actual = {path.name: path.read_bytes() for path in rebuilt.iterdir()}
    return sorted(name for name in expected.keys() | actual.keys() if expected.get(name) != actual.get(name))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    steps = parser.add_subparsers(dest="step", required=True)
    source = steps.add_parser("source")
    source.add_argument("--reference", type=pathlib.Path, default=REFERENCE)
    source.add_argument("--routes", type=pathlib.Path, default=ROUTES)
    source.add_argument("--output", type=pathlib.Path, required=True)
    serving = steps.add_parser("serving")
    serving.add_argument("--source", type=pathlib.Path, required=True)
    serving.add_argument("--numeric", type=pathlib.Path, required=True)
    serving.add_argument("--runtime", type=pathlib.Path, required=True)
    serving.add_argument("--output", type=pathlib.Path, required=True)
    check = steps.add_parser("check")
    check.add_argument("--reference", type=pathlib.Path, default=REFERENCE)
    check.add_argument("--routes", type=pathlib.Path, default=ROUTES)
    args = parser.parse_args()
    if args.step == "source":
        print("source manifest", build_source(args.reference, args.routes, args.output))
    elif args.step == "serving":
        reports = dict(feature_parity=args.numeric, runtime_validation=args.runtime)
        print("serving manifest", build_serving(args.source, reports, args.output))
    else:
        differing = differences(args.reference, args.routes)
        if differing:
            raise SystemExit("model reference is not reproducible: " + ", ".join(differing))
        print("model reference is reproducible")


if __name__ == "__main__":
    main()

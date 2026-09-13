#!/usr/bin/env python3
"""Fallback only: read sha256<TAB>HTTPS-URL lines from stdin without echoing URLs."""

from __future__ import annotations

import hashlib
import os
import pathlib
import re
import sys
import tempfile
import urllib.parse
import urllib.request


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


if len(sys.argv) != 2:
    fail("usage: download-presigned.py DESTINATION_DIRECTORY < private-stdin-plan")

destination = pathlib.Path(sys.argv[1]).resolve()
destination.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(destination, 0o700)

for ordinal, original in enumerate(sys.stdin, start=1):
    line = original.rstrip("\n")
    try:
        wanted, url = line.split("\t", 1)
    except ValueError:
        fail(f"download plan line {ordinal} is invalid")
    if not re.fullmatch(r"[0-9a-f]{64}", wanted):
        fail(f"download plan digest {ordinal} is invalid")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        fail(f"download plan URL {ordinal} is not approved HTTPS")
    target = destination / f"{wanted}.download"
    if target.exists():
        if hashlib.sha256(target.read_bytes()).hexdigest() == wanted:
            continue
        fail(f"existing artifact {ordinal} has a different digest")
    handle, temporary_name = tempfile.mkstemp(prefix=".download-", dir=destination)
    temporary = pathlib.Path(temporary_name)
    try:
        os.fchmod(handle, 0o600)
        with os.fdopen(handle, "wb") as output:
            try:
                with urllib.request.urlopen(url, timeout=60) as response:
                    digest = hashlib.sha256()
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
                        digest.update(chunk)
            except Exception:
                fail(f"HTTPS artifact download {ordinal} failed")
        if digest.hexdigest() != wanted:
            fail(f"HTTPS artifact digest {ordinal} mismatched")
        temporary.replace(target)
        os.chmod(target, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()

print("fallback HTTPS artifacts downloaded and hash-verified")

import json
import re
from pathlib import Path


MAX_EXECUTION_EVIDENCE_BYTES = 200_000
_DIGEST = re.compile(r"^[0-9a-f]{64}$")


class ExecutionEvidenceError(ValueError):
    pass


def load_execution_evidence(path, head_sha):
    path = Path(path)
    try:
        if path.stat().st_size > MAX_EXECUTION_EVIDENCE_BYTES:
            raise ExecutionEvidenceError("execution evidence exceeds the size limit")
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ExecutionEvidenceError(f"cannot read execution evidence: {path}") from error
    if raw.get("schemaVersion") != 1 or raw.get("headSha") != head_sha:
        raise ExecutionEvidenceError(
            "execution evidence does not match the reviewed head SHA"
        )
    checks = raw.get("checks")
    if not isinstance(checks, list) or len(checks) > 50:
        raise ExecutionEvidenceError("execution evidence checks must be a bounded list")
    normalized = []
    seen = set()
    for check in checks:
        if not isinstance(check, dict):
            raise ExecutionEvidenceError("execution evidence check must be an object")
        check_id = check.get("id")
        command = check.get("command")
        if (
            not isinstance(check_id, str)
            or not check_id
            or check_id in seen
            or not isinstance(command, list)
            or not command
            or not all(isinstance(item, str) and item for item in command)
            or check.get("exitCode") != 0
            or not _DIGEST.fullmatch(str(check.get("outputSha256", "")))
        ):
            raise ExecutionEvidenceError("execution evidence check is invalid")
        seen.add(check_id)
        normalized.append(
            {
                "id": check_id[:200],
                "command": command[:50],
                "exitCode": 0,
                "outputSha256": check["outputSha256"],
            }
        )
    return normalized

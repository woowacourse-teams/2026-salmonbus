import json
from pathlib import Path, PurePosixPath


MAX_SEMANTIC_CONTEXT_BYTES = 1_000_000


class SemanticContextError(ValueError):
    pass


def load_semantic_context(path, head_sha, mapped_paths):
    """Load bounded navigation data produced by an unprivileged code analyzer."""
    path = Path(path)
    try:
        if path.stat().st_size > MAX_SEMANTIC_CONTEXT_BYTES:
            raise SemanticContextError("semantic context exceeds the size limit")
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SemanticContextError(f"cannot read semantic context: {path}") from error

    if raw.get("schemaVersion") != 1:
        raise SemanticContextError("semantic context must use schemaVersion 1")
    if raw.get("headSha") != head_sha:
        raise SemanticContextError("semantic context does not match the reviewed head SHA")
    if raw.get("provider") not in {"lsp", "typescript-language-service"}:
        raise SemanticContextError("semantic context provider is unsupported")

    allowed_paths = set(mapped_paths)
    files = raw.get("files")
    if not isinstance(files, list) or len(files) > 100:
        raise SemanticContextError("semantic context files must be a bounded list")

    normalized = []
    for file_context in files:
        if not isinstance(file_context, dict) or file_context.get("path") not in allowed_paths:
            raise SemanticContextError("semantic context contains an unmapped path")
        symbols = file_context.get("symbols", [])
        if not isinstance(symbols, list) or len(symbols) > 500:
            raise SemanticContextError("semantic context symbols must be a bounded list")
        normalized_symbols = []
        for symbol in symbols:
            if not isinstance(symbol, dict):
                raise SemanticContextError("semantic context symbol must be an object")
            name = symbol.get("name")
            kind = symbol.get("kind")
            line = symbol.get("line")
            references = symbol.get("references", [])
            if (
                not isinstance(name, str)
                or not isinstance(kind, str)
                or not isinstance(line, int)
                or isinstance(line, bool)
                or line < 1
                or not isinstance(references, list)
                or len(references) > 100
            ):
                raise SemanticContextError("semantic context symbol is invalid")
            normalized_references = []
            for reference in references:
                if (
                    not isinstance(reference, dict)
                    or not _repository_path(reference.get("path"))
                    or not isinstance(reference.get("line"), int)
                    or isinstance(reference.get("line"), bool)
                    or reference["line"] < 1
                ):
                    raise SemanticContextError("semantic context reference is invalid")
                normalized_references.append(
                    {"path": reference["path"], "line": reference["line"]}
                )
            normalized_symbols.append(
                {
                    "name": name[:200],
                    "kind": kind[:100],
                    "line": line,
                    "references": normalized_references,
                }
            )
        normalized.append({"path": file_context["path"], "symbols": normalized_symbols})

    return {
        "schemaVersion": 1,
        "provider": raw["provider"],
        "headSha": raw["headSha"],
        "files": normalized,
    }


def _repository_path(value):
    if not isinstance(value, str) or not value:
        return False
    path = PurePosixPath(value)
    return not path.is_absolute() and ".." not in path.parts

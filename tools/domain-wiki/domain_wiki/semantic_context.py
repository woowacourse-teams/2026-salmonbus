import json
import subprocess
from pathlib import Path, PurePosixPath


MAX_SEMANTIC_CONTEXT_BYTES = 80_000


class SemanticContextError(ValueError):
    pass


def load_semantic_context(path, head_sha, mapped_paths, repository):
    """Load navigation data and verify every reported location against the reviewed commit."""
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
    file_cache = {}
    for file_context in files:
        if not isinstance(file_context, dict) or file_context.get("path") not in allowed_paths:
            raise SemanticContextError("semantic context contains an unmapped path")
        symbols = file_context.get("symbols", [])
        call_targets = file_context.get("callTargets", [])
        if not isinstance(symbols, list) or len(symbols) > 500:
            raise SemanticContextError("semantic context symbols must be a bounded list")
        if not isinstance(call_targets, list) or len(call_targets) > 100:
            raise SemanticContextError("semantic context call targets must be a bounded list")
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
            symbol_lines = _git_lines(repository, head_sha, file_context["path"], file_cache)
            if line > len(symbol_lines) or name not in symbol_lines[line - 1]:
                raise SemanticContextError(
                    "semantic context symbol does not match the reviewed commit"
                )
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
                reference_lines = _git_lines(
                    repository, head_sha, reference["path"], file_cache
                )
                if (
                    reference["line"] > len(reference_lines)
                    or name not in reference_lines[reference["line"] - 1]
                ):
                    raise SemanticContextError(
                        "semantic context reference does not match the reviewed commit"
                    )
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
        normalized_call_targets = [
            _validated_call_target(
                item, file_context["path"], repository, head_sha, file_cache
            )
            for item in call_targets
        ]
        normalized.append({
            "path": file_context["path"],
            "symbols": normalized_symbols,
            "callTargets": normalized_call_targets,
        })

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


def _validated_call_target(item, source_path, repository, head_sha, cache):
    if not isinstance(item, dict):
        raise SemanticContextError("semantic context call target must be an object")
    call = item.get("call")
    call_line = item.get("callLine")
    definition_path = item.get("definitionPath")
    definition_line = item.get("definitionLine")
    snippet = item.get("definitionSnippet")
    if (
        not isinstance(call, str)
        or not call
        or len(call) > 200
        or not isinstance(call_line, int)
        or isinstance(call_line, bool)
        or call_line < 1
        or not _repository_path(definition_path)
        or not isinstance(definition_line, int)
        or isinstance(definition_line, bool)
        or definition_line < 1
        or not isinstance(snippet, str)
        or not snippet
        or len(snippet) > 8_000
    ):
        raise SemanticContextError("semantic context call target is invalid")
    source_lines = _git_lines(repository, head_sha, source_path, cache)
    definition_lines = _git_lines(repository, head_sha, definition_path, cache)
    snippet_lines = snippet.splitlines()
    if call_line > len(source_lines) or call not in source_lines[call_line - 1]:
        raise SemanticContextError(
            "semantic context call target does not match the reviewed commit"
        )
    actual = definition_lines[definition_line - 1:definition_line - 1 + len(snippet_lines)]
    if actual != snippet_lines:
        raise SemanticContextError(
            "semantic context definition does not match the reviewed commit"
        )
    return {
        "call": call,
        "callLine": call_line,
        "definitionPath": definition_path,
        "definitionLine": definition_line,
        "definitionSnippet": snippet,
    }


def _git_lines(repository, head_sha, path, cache):
    if path in cache:
        return cache[path]
    result = subprocess.run(
        ["git", "-C", str(repository), "show", f"{head_sha}:{path}"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SemanticContextError(
            f"semantic context path is absent from the reviewed commit: {path}"
        )
    cache[path] = result.stdout.splitlines()
    return cache[path]

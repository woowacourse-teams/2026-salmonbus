import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Tuple


class GitError(RuntimeError):
    pass


class GitRevisionError(GitError):
    pass


class GitDiffTooLargeError(GitError):
    pass


@dataclass(frozen=True)
class GitDiff:
    base_sha: str
    head_sha: str
    changed_paths: Tuple[str, ...]


def read_git_diff(
    repository: Path,
    base_revision: str,
    head_revision: str,
    *,
    use_merge_base: bool = False,
) -> GitDiff:
    repository = Path(repository).resolve()
    _ensure_repository(repository)
    base_sha = _resolve_commit(repository, base_revision)
    head_sha = _resolve_commit(repository, head_revision)
    if use_merge_base:
        base_sha = _run_git(
            repository, "merge-base", base_sha, head_sha
        ).stdout.strip()

    names = _run_git(
        repository,
        "diff",
        "--name-only",
        "-z",
        "--no-ext-diff",
        base_sha,
        head_sha,
        "--",
        text=False,
    ).stdout
    paths = sorted(path.decode("utf-8") for path in names.split(b"\0") if path)
    return GitDiff(base_sha=base_sha, head_sha=head_sha, changed_paths=tuple(paths))


def read_patches(
    repository: Path,
    base_sha: str,
    head_sha: str,
    paths: Tuple[str, ...],
    max_patch_bytes: int,
):
    if max_patch_bytes < 1:
        raise ValueError("max_patch_bytes must be positive")
    patches = {}
    total_bytes = 0
    for path in paths:
        patch = _read_patch(Path(repository).resolve(), base_sha, head_sha, path)
        total_bytes += len(patch.encode("utf-8"))
        if total_bytes > max_patch_bytes:
            raise GitDiffTooLargeError(
                f"mapped Git diff exceeds {max_patch_bytes} bytes; narrow the revisions or document paths"
            )
        patches[path] = patch
    return patches


def _ensure_repository(repository: Path) -> None:
    result = _run_git(repository, "rev-parse", "--is-inside-work-tree", check=False)
    if result.returncode != 0 or result.stdout.strip() != "true":
        raise GitError(f"not a Git work tree: {repository}")


def _resolve_commit(repository: Path, revision: str) -> str:
    if not revision:
        raise GitRevisionError("Git revision must not be empty")
    result = _run_git(
        repository,
        "rev-parse",
        "--verify",
        f"{revision}^{{commit}}",
        check=False,
    )
    if result.returncode != 0:
        raise GitRevisionError(f"unknown Git revision: {revision}")
    return result.stdout.strip()


def _read_patch(repository: Path, base_sha: str, head_sha: str, path: str) -> str:
    return _run_git(
        repository,
        "diff",
        "--no-ext-diff",
        "--no-color",
        "--unified=3",
        base_sha,
        head_sha,
        "--",
        path,
    ).stdout


def _run_git(repository: Path, *arguments: str, check: bool = True, text: bool = True):
    try:
        return subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=check,
            capture_output=True,
            text=text,
        )
    except FileNotFoundError as error:
        raise GitError("git executable was not found") from error
    except subprocess.CalledProcessError as error:
        stderr = error.stderr.strip() if isinstance(error.stderr, str) else ""
        raise GitError(stderr or f"git command failed: {' '.join(arguments)}") from error

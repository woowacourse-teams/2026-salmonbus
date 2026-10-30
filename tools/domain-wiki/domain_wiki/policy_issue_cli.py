import argparse
import json
import os
import subprocess
from pathlib import Path

from .comment_style import load_namukkun_style
from .confluence import ConfluenceClient
from .git_diff import read_git_diff, read_patches
from .openai import MAX_PATCH_BYTES, OpenAIClient
from .policy_issue_review import (
    load_active_rules,
    render_comment,
    run_issue_review,
    verify_confluence_evidence,
)
from .semantic_context import load_semantic_context


def main(argv=None):
    parser = argparse.ArgumentParser(description="Review a code diff against active Git Wiki policies")
    parser.add_argument("--repository", default=".")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--target-repository", required=True)
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--pull-request-context", required=True)
    parser.add_argument("--semantic-context")
    parser.add_argument("--wiki-directory", required=True)
    parser.add_argument(
        "--comment-style-directory",
        default=str(Path(__file__).resolve().parents[1] / "skills" / "namukkun"),
    )
    parser.add_argument("--output-directory", default=".domain-wiki/output")
    parser.add_argument("--threshold", type=int, default=80)
    args = parser.parse_args(argv)

    repository = Path(args.repository).resolve()
    wiki = Path(args.wiki_directory).resolve()
    pull_request = _load_pull_request_context(args.pull_request_context)
    rules = load_active_rules(
        wiki / "Policy-Registry.json", args.target_repository, allow_empty=True
    )
    output = Path(args.output_directory)
    output.mkdir(parents=True, exist_ok=True)
    if not rules:
        result = {
            "stages": [], "threshold": args.threshold, "candidateCount": 0,
            "evaluations": [], "issues": [], "usage": [], "models": {},
            "sourceUrl": args.source_url, "wikiCommit": _wiki_commit(wiki),
            "pullRequest": pull_request, "changedPaths": [], "mappedPaths": [],
            "status": "no-active-policy",
        }
        (output / "policy-result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (output / "policy-comment.md").write_text(
            render_comment(result), encoding="utf-8"
        )
        return
    diff = read_git_diff(repository, args.base, args.head, use_merge_base=True)
    mapped_paths = tuple(
        path for path in diff.changed_paths
        if any(_path_matches(path, pattern) for rule in rules for pattern in rule.code_paths)
    )
    if not mapped_paths:
        result = {
            "stages": [], "threshold": args.threshold, "candidateCount": 0,
            "evaluations": [], "issues": [], "usage": [], "models": {},
            "sourceUrl": args.source_url, "wikiCommit": _wiki_commit(wiki),
            "pullRequest": pull_request,
            "baseSha": diff.base_sha, "headSha": diff.head_sha,
            "changedPaths": list(diff.changed_paths), "mappedPaths": [],
            "status": "no-mapped-changes",
        }
        (output / "policy-result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (output / "policy-comment.md").write_text(
            render_comment(result), encoding="utf-8"
        )
        return
    if not all(
        os.environ.get(name)
        for name in ("OPENAI_API_KEY", "ATLASSIAN_EMAIL", "ATLASSIAN_API_TOKEN")
    ):
        result = {
            "stages": [], "threshold": args.threshold, "candidateCount": 0,
            "evaluations": [], "issues": [], "usage": [], "models": {},
            "sourceUrl": args.source_url, "wikiCommit": _wiki_commit(wiki),
            "pullRequest": pull_request, "changedPaths": [], "mappedPaths": [],
            "status": "credentials-missing",
        }
        (output / "policy-result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (output / "policy-comment.md").write_text(
            render_comment(result), encoding="utf-8"
        )
        return
    confluence = ConfluenceClient(
        os.environ.get("CONFLUENCE_BASE_URL", "https://higgs95.atlassian.net/wiki"),
        os.environ.get("ATLASSIAN_EMAIL"),
        os.environ.get("ATLASSIAN_API_TOKEN"),
    )
    verify_confluence_evidence(confluence, rules)

    patches = read_patches(
        repository, diff.base_sha, diff.head_sha, mapped_paths, MAX_PATCH_BYTES
    )
    semantic_context = None
    if args.semantic_context:
        semantic_context = load_semantic_context(
            args.semantic_context, diff.head_sha, mapped_paths
        )
    cheap = OpenAIClient(
        os.environ.get("OPENAI_API_KEY"),
        model=os.environ.get("POLICY_DRAFT_MODEL", "gpt-6-luna"),
        reasoning_effort=os.environ.get("POLICY_DRAFT_REASONING", "low"),
    )
    strong = OpenAIClient(
        os.environ.get("OPENAI_API_KEY"),
        model=os.environ.get("POLICY_REVIEW_MODEL", "gpt-6.1-sol"),
        reasoning_effort=os.environ.get("POLICY_REVIEW_REASONING", "high"),
    )
    comment_style = load_namukkun_style(args.comment_style_directory)
    wiki_commit = _wiki_commit(wiki)
    result = run_issue_review(
        cheap_client=cheap,
        strong_client=strong,
        rules=rules,
        patches=patches,
        repository=repository,
        head_sha=diff.head_sha,
        wiki_commit=wiki_commit,
        source_url=args.source_url,
        pull_request=pull_request,
        semantic_context=semantic_context,
        comment_style=comment_style,
        threshold=args.threshold,
    )
    result.update({
        "baseSha": diff.base_sha,
        "headSha": diff.head_sha,
        "changedPaths": list(diff.changed_paths),
        "mappedPaths": list(mapped_paths),
    })
    (output / "policy-result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "policy-comment.md").write_text(render_comment(result), encoding="utf-8")


def _path_matches(path, pattern):
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3])
    return path == pattern


def _wiki_commit(wiki):
    return subprocess.check_output(
        ["git", "-C", str(wiki), "rev-parse", "HEAD"], text=True
    ).strip()


def _load_pull_request_context(path):
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read pull request context: {path}") from error
    title = raw.get("title")
    body = raw.get("body") or ""
    if not isinstance(title, str) or not isinstance(body, str):
        raise ValueError("pull request title and body must be strings")
    return {"title": title[:500], "body": body[:20_000]}


if __name__ == "__main__":
    main()

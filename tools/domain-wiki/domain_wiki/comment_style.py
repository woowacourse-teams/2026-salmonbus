from pathlib import Path


class CommentStyleError(ValueError):
    pass


NAMUKKUN_MEMES = (
    "대충 하면~? 꼭 두 번 일한다~",
    "호미로 막을 걸~? 가래로 막는다~",
    "가랑비에~? 옷 젖는다~",
    "돌다리도~? 두들겨 보고 건너라~",
    "땅을 파봐라, 토큰 하나라도 나오나.",
    "배보다~? 배꼽이 더 크다~",
    "모델도 적재적소에 써야 합니다.",
    "이런 게 온고지신입니다.",
    "여기까지 하면 전화위복입니다.",
)


def load_namukkun_style(skill_directory):
    root = Path(skill_directory).resolve()
    skill = _read(root / "SKILL.md")
    memes = _read(root / "references" / "memes.md")
    return {
        "name": "namukkun",
        "instructions": skill,
        "memes": memes,
        "boundary": (
            "Apply this style only after deciding the policy verdict. It may change only "
            "humanExplanation, humanTrigger, and humanSuggestion. Never change scores, "
            "decision, rationale, policy text, code evidence, or machine-readable facts. "
            "Never put scores, thresholds, policy IDs, candidate IDs, or unexplained "
            "implementation jargon in human-visible fields. Explain the policy as a natural "
            "Korean behavior rule. Keep machine identifiers only in the collapsed JSON. "
            "Use at most one catalog meme in the entire issue, only at the end of "
            "humanSuggestion. Omit it when the catalog has no exact match or the review "
            "concerns an incident, security, data loss, cost charge, apology, or blame."
        ),
    }


def _read(path):
    try:
        value = path.read_text(encoding="utf-8")
    except OSError as error:
        raise CommentStyleError(f"cannot read comment style: {path}") from error
    if not value.strip():
        raise CommentStyleError(f"comment style is empty: {path}")
    if len(value) > 20_000:
        raise CommentStyleError(f"comment style is too large: {path}")
    return value

import argparse
import json
import os
from pathlib import Path

from .discord_notify import (
    build_wiki_approval_embeds,
    build_wiki_approval_message,
    send_discord_message,
)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Preview or send a Discord Wiki approval notification"
    )
    parser.add_argument("--approval")
    parser.add_argument("--result")
    parser.add_argument("--repository")
    parser.add_argument("--run-id")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--lab", action="store_true")
    parser.add_argument("--send", action="store_true")
    args = parser.parse_args(argv)

    if args.test:
        message = "Salmonbus Wiki 승인 알림 연결 테스트입니다."
        embeds = None
    elif args.lab:
        approval, result = _lab_data()
        repository = args.repository or "korogoo/salmonbus-domain-wiki-lab"
        run_id = args.run_id or "1"
        message = build_wiki_approval_message(
            approval, result, repository, run_id
        )
        embeds = build_wiki_approval_embeds(
            approval, result, repository, run_id
        )
    else:
        if not all((args.approval, args.result, args.repository, args.run_id)):
            parser.error(
                "--approval, --result, --repository, and --run-id are required"
            )
        approval = _read_json(args.approval)
        result = _read_json(args.result)
        message = build_wiki_approval_message(
            approval, result, args.repository, args.run_id
        )
        embeds = build_wiki_approval_embeds(
            approval, result, args.repository, args.run_id
        )
    if not args.send:
        print(message)
        if embeds:
            for field in embeds[0].get("fields", []):
                print(f"\n{field['name']}\n{field['value']}")
        return
    send_discord_message(
        os.environ.get("DISCORD_WEBHOOK_URL"), message, embeds=embeds
    )
    print("Discord Wiki approval notification sent.")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _lab_data():
    approval = {
        "kind": "salmonbus-wiki-update-approval",
        "pullRequest": {
            "number": 26,
            "title": "[LAB] Wiki 승인 알림 확인",
        },
    }
    result = {
        "status": "approval-required",
        "accepted": [
            {
                "document": "Policy-POL-DSP-001.md",
                "currentText": "화면에는 최신 예보와 차량 정보를 표시한다.",
                "replacementText": (
                    "화면에는 같은 시점에 수집한 예보와 차량 정보만 표시하고, "
                    "새 정보의 짝이 없으면 기존 화면을 유지한다."
                ),
            },
            {
                "document": "Policy-Index.md",
                "currentText": "정책 검토가 필요하다.",
                "replacementText": "정책 변경은 사람의 승인을 받은 뒤 Wiki에 반영한다.",
            },
        ],
        "rejected": [{"reason": "회의록 근거가 부족함"}],
    }
    return approval, result


if __name__ == "__main__":
    main()

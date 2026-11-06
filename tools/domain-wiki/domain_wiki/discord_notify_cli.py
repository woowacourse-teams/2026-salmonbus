import argparse
import json
import os
from pathlib import Path

from .discord_notify import build_wiki_approval_message, send_discord_message


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Preview or send a Discord Wiki approval notification"
    )
    parser.add_argument("--approval")
    parser.add_argument("--result")
    parser.add_argument("--repository")
    parser.add_argument("--run-id")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--send", action="store_true")
    args = parser.parse_args(argv)

    if args.test:
        message = "Salmonbus Wiki 승인 알림 연결 테스트입니다."
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
    if not args.send:
        print(message)
        return
    send_discord_message(os.environ.get("DISCORD_WEBHOOK_URL"), message)
    print("Discord Wiki approval notification sent.")


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()

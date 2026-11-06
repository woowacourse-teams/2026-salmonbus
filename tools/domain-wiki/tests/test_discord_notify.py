import json
import io
import os
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from domain_wiki.discord_notify import (
    DiscordNotificationError,
    DiscordTransport,
    build_wiki_approval_embeds,
    build_wiki_approval_message,
    send_discord_message,
)
from domain_wiki.discord_notify_cli import main as discord_notify_main


class FakeTransport:
    def __init__(self, status=204, body=""):
        self.status = status
        self.body = body
        self.calls = []

    def request(self, url, body):
        self.calls.append((url, json.loads(body.decode("utf-8"))))
        return self.status, self.body


class DiscordNotifyTest(unittest.TestCase):
    def setUp(self):
        self.approval = {
            "kind": "salmonbus-wiki-update-approval",
            "baseSha": "a" * 40,
            "headSha": "b" * 40,
            "approvalDigest": "c" * 64,
            "pullRequest": {"number": 192, "title": "정책 Wiki 자동화"},
        }
        self.result = {
            "status": "approval-required",
            "accepted": [
                {
                    "document": "Policy.md",
                    "currentText": "현재 동작",
                    "replacementText": "새 동작",
                },
                {
                    "document": "Index.md",
                    "currentText": "검토 전",
                    "replacementText": "검토 후",
                },
            ],
            "rejected": [{}],
        }

    def test_builds_a_message_with_the_one_click_approval_link(self):
        message = build_wiki_approval_message(
            self.approval, self.result, "owner/repository", "12345"
        )

        self.assertIn("PR #192 정책 Wiki 자동화", message)
        self.assertIn("변경 2건 · 탈락 1건", message)
        self.assertIn("actions/runs/12345", message)
        self.assertNotIn("approval_digest", message)

        embeds = build_wiki_approval_embeds(
            self.approval, self.result, "owner/repository", "12345"
        )
        self.assertEqual("📄 Policy.md", embeds[0]["fields"][0]["name"])
        self.assertIn("현재 동작", embeds[0]["fields"][0]["value"])
        self.assertIn("새 동작", embeds[0]["fields"][0]["value"])
        self.assertIn("Approve and deploy", embeds[0]["footer"]["text"])

    def test_sends_without_allowing_mentions(self):
        transport = FakeTransport()
        embeds = [{"title": "approval"}]
        send_discord_message(
            "https://discord.com/api/webhooks/1/token",
            "approval",
            transport,
            embeds,
        )

        payload = transport.calls[0][1]
        self.assertEqual({"parse": []}, payload["allowed_mentions"])
        self.assertEqual("approval", payload["content"])
        self.assertEqual(embeds, payload["embeds"])

    def test_http_transport_identifies_the_client_to_discord(self):
        class Response:
            status = 204

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return b""

        with patch("urllib.request.urlopen", return_value=Response()) as urlopen:
            status, body = DiscordTransport().request(
                "https://discord.com/api/webhooks/1/token", b"{}"
            )

        request = urlopen.call_args.args[0]
        self.assertEqual(204, status)
        self.assertEqual("", body)
        self.assertEqual(
            "Salmonbus-Domain-Wiki/1.0", request.get_header("User-agent")
        )
        self.assertEqual("application/json", request.get_header("Content-type"))

    def test_requires_a_valid_webhook_url_only_when_sending(self):
        with self.assertRaisesRegex(DiscordNotificationError, "missing or invalid"):
            send_discord_message(None, "approval", FakeTransport())

    def test_local_test_message_can_be_previewed_without_a_webhook(self):
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), redirect_stdout(output):
            discord_notify_main(["--test"])

        self.assertEqual(
            "Salmonbus Wiki 승인 알림 연결 테스트입니다.\n",
            output.getvalue(),
        )

    def test_lab_preview_shows_before_and_after_text(self):
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), redirect_stdout(output):
            discord_notify_main(["--lab"])

        self.assertIn("[LAB] Wiki 승인 알림 확인", output.getvalue())
        self.assertIn("화면에는 최신 예보", output.getvalue())
        self.assertIn("새 정보의 짝이 없으면", output.getvalue())


if __name__ == "__main__":
    unittest.main()

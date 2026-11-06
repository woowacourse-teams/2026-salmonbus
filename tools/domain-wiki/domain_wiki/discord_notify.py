import json
import urllib.error
import urllib.request


MAX_DISCORD_CONTENT = 2_000
MAX_DISCORD_EMBED_FIELDS = 3


class DiscordNotificationError(ValueError):
    pass


class DiscordTransport:
    def request(self, url, body):
        request = urllib.request.Request(
            url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "Salmonbus-Domain-Wiki/1.0",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", errors="replace")


def build_wiki_approval_message(approval, result, repository, run_id):
    context = _notification_context(approval, result, repository, run_id)
    return "\n".join(
        [
            "## Wiki 변경 승인이 필요해요",
            f"PR #{context['number']} {context['title']}",
            f"변경 {context['acceptedCount']}건 · 탈락 {context['rejectedCount']}건",
            f"[전체 변경안 보기]({context['prUrl']}) · [승인 화면 열기]({context['actionUrl']})",
        ]
    )


def build_wiki_approval_embeds(approval, result, repository, run_id):
    context = _notification_context(approval, result, repository, run_id)
    fields = []
    for item in result.get("accepted", [])[:MAX_DISCORD_EMBED_FIELDS]:
        document = str(item.get("document", "Wiki 문서"))[:256]
        current = _code_block(item.get("currentText", ""), 360)
        replacement = _code_block(item.get("replacementText", ""), 360)
        fields.append(
            {
                "name": f"📄 {document}",
                "value": f"**현재**\n{current}\n**변경 후**\n{replacement}",
                "inline": False,
            }
        )
    hidden = context["acceptedCount"] - len(fields)
    if hidden > 0:
        fields.append(
            {
                "name": "나머지 변경안",
                "value": f"PR 댓글에서 {hidden}건을 더 확인할 수 있어요.",
                "inline": False,
            }
        )
    return [
        {
            "title": "Wiki 변경 승인 필요",
            "url": context["prUrl"],
            "description": (
                f"**PR #{context['number']}** {context['title']}\n"
                f"변경 **{context['acceptedCount']}건** · 탈락 **{context['rejectedCount']}건**\n\n"
                f"[전체 변경안 보기]({context['prUrl']}) · "
                f"[Actions에서 승인]({context['actionUrl']})"
            ),
            "color": 0xF2C94C,
            "fields": fields,
            "footer": {
                "text": "변경 전후를 확인한 뒤 Approve and deploy를 선택해 주세요."
            },
        }
    ]


def _notification_context(approval, result, repository, run_id):
    if approval.get("kind") != "salmonbus-wiki-update-approval":
        raise DiscordNotificationError("unsupported Wiki approval request")
    if result.get("status") != "approval-required":
        raise DiscordNotificationError("Wiki approval is not required")
    if not _repository_name(repository) or not str(run_id).isdigit():
        raise DiscordNotificationError("repository or workflow run ID is invalid")
    pull_request = approval.get("pullRequest", {})
    number = pull_request.get("number")
    title = pull_request.get("title", "")
    if not isinstance(number, int) or isinstance(number, bool):
        raise DiscordNotificationError("pull request number is missing")
    if not isinstance(title, str):
        title = ""
    pr_url = f"https://github.com/{repository}/pull/{number}"
    action_url = (
        f"https://github.com/{repository}/actions/runs/{run_id}"
    )
    return {
        "number": number,
        "title": title[:200],
        "prUrl": pr_url,
        "actionUrl": action_url,
        "acceptedCount": len(result.get("accepted", [])),
        "rejectedCount": len(result.get("rejected", [])),
    }


def send_discord_message(webhook_url, content, transport=None, embeds=None):
    if not isinstance(webhook_url, str) or not webhook_url.startswith(
        "https://discord.com/api/webhooks/"
    ):
        raise DiscordNotificationError("DISCORD_WEBHOOK_URL is missing or invalid")
    if not isinstance(content, str) or not content or len(content) > MAX_DISCORD_CONTENT:
        raise DiscordNotificationError("Discord message is empty or too long")
    notification = {"content": content, "allowed_mentions": {"parse": []}}
    if embeds:
        notification["embeds"] = embeds
    payload = json.dumps(notification, ensure_ascii=False).encode("utf-8")
    status, body = (transport or DiscordTransport()).request(webhook_url, payload)
    if status < 200 or status >= 300:
        raise DiscordNotificationError(
            f"Discord webhook returned HTTP {status}: {body[:200]}"
        )


def _repository_name(value):
    if not isinstance(value, str) or value.count("/") != 1:
        return False
    owner, repository = value.split("/", 1)
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")
    return bool(owner and repository and set(owner) <= allowed and set(repository) <= allowed)


def _code_block(value, maximum):
    text = str(value).replace("```", "''' ").strip()
    if len(text) > maximum:
        text = text[:maximum - 3] + "..."
    return f"```\n{text or '(빈 문장)'}\n```"

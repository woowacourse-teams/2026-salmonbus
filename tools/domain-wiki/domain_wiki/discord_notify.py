import json
import urllib.error
import urllib.request


MAX_DISCORD_CONTENT = 2_000


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
    message = "\n".join(
        [
            "## Wiki 변경 승인이 필요해요",
            f"PR #{number} {title[:200]}",
            f"변경 후보: {len(result.get('accepted', []))}건",
            f"변경안 확인: {pr_url}",
            f"승인 화면: {action_url}",
            "Actions에서 `Review deployments`를 누른 뒤 `Approve and deploy`를 선택해 주세요.",
        ]
    )
    if len(message) > MAX_DISCORD_CONTENT:
        raise DiscordNotificationError("Discord approval message is too long")
    return message


def send_discord_message(webhook_url, content, transport=None):
    if not isinstance(webhook_url, str) or not webhook_url.startswith(
        "https://discord.com/api/webhooks/"
    ):
        raise DiscordNotificationError("DISCORD_WEBHOOK_URL is missing or invalid")
    if not isinstance(content, str) or not content or len(content) > MAX_DISCORD_CONTENT:
        raise DiscordNotificationError("Discord message is empty or too long")
    payload = json.dumps(
        {"content": content, "allowed_mentions": {"parse": []}},
        ensure_ascii=False,
    ).encode("utf-8")
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

import base64
import json
import urllib.error
import urllib.parse
import urllib.request


class ConfluenceError(ValueError):
    pass


class UrlLibTransport:
    def request(self, method, url, headers):
        request = urllib.request.Request(url=url, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"message": raw}
            return error.code, payload


class ConfluenceClient:
    """Read-only access to meeting evidence.

    Git Wiki is the only documentation write target, so this client intentionally
    exposes no create, update, or delete operation.
    """

    def __init__(self, base_url, email, api_token, transport=None):
        if not email or not api_token:
            raise ConfluenceError("Confluence email and API token are required")
        self.base_url = base_url.rstrip("/")
        credential = base64.b64encode(
            f"{email}:{api_token}".encode("utf-8")
        ).decode("ascii")
        self.headers = {
            "Accept": "application/json",
            "Authorization": f"Basic {credential}",
        }
        self.transport = transport or UrlLibTransport()

    def get_page(self, page_id):
        query = urllib.parse.urlencode({"body-format": "storage"})
        status, payload = self.transport.request(
            "GET", f"{self.base_url}/api/v2/pages/{page_id}?{query}", self.headers
        )
        if status < 200 or status >= 300:
            message = payload.get("message") if isinstance(payload, dict) else payload
            raise ConfluenceError(f"Confluence API {status}: {message}")
        if not isinstance(payload, dict):
            raise ConfluenceError("Confluence API response must be an object")
        return payload

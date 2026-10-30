import json
import urllib.error
import urllib.request


MAX_MODEL_INPUT_CHARACTERS = 200_000
MAX_PATCH_BYTES = 40_000


class OpenAIError(ValueError):
    pass


class UrlLibTransport:
    def request(self, method, url, headers, body=None):
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"error": {"message": raw}}
            return error.code, payload


class OpenAIClient:
    def __init__(
        self,
        api_key,
        *,
        model="gpt-6-luna",
        reasoning_effort="medium",
        max_input_characters=MAX_MODEL_INPUT_CHARACTERS,
        max_output_tokens=8_000,
        transport=None,
    ):
        if not api_key:
            raise OpenAIError("OPENAI_API_KEY is required")
        self.api_key = api_key
        self.model = model
        self.reasoning_effort = reasoning_effort
        self.max_input_characters = max_input_characters
        self.max_output_tokens = max_output_tokens
        self.transport = transport or UrlLibTransport()

    def review(self, prompt_payload, schema):
        instructions = prompt_payload["systemInstructions"]
        dynamic_payload = {
            key: value
            for key, value in prompt_payload.items()
            if key != "systemInstructions"
        }
        serialized = json.dumps(
            dynamic_payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        input_characters = len(instructions) + len(serialized)
        if input_characters > self.max_input_characters:
            raise OpenAIError(
                "model input is too large: "
                f"{input_characters} > {self.max_input_characters} characters"
            )
        response_schema = {
            key: value
            for key, value in schema.items()
            if key not in {"$schema", "$id", "title"}
        }
        request_body = {
            "model": self.model,
            "input": [
                {
                    "role": "developer",
                    "content": [{"type": "input_text", "text": instructions}],
                },
                {"role": "user", "content": serialized},
            ],
            "reasoning": {"effort": self.reasoning_effort},
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "salmonbus_policy_review",
                    "strict": True,
                    "schema": response_schema,
                }
            },
            "max_output_tokens": self.max_output_tokens,
            "store": False,
        }
        status, response = self.transport.request(
            "POST",
            "https://api.openai.com/v1/responses",
            {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
        )
        if status < 200 or status >= 300:
            message = response.get("error", {}).get("message", "unknown error")
            raise OpenAIError(f"OpenAI API {status}: {message}")
        if response.get("status") == "incomplete":
            reason = response.get("incomplete_details", {}).get("reason", "unknown")
            raise OpenAIError(f"OpenAI response incomplete: {reason}")
        output_text = _output_text(response)
        try:
            review = json.loads(output_text)
        except json.JSONDecodeError as error:
            raise OpenAIError("OpenAI response is not valid JSON") from error
        if not isinstance(review, dict):
            raise OpenAIError("OpenAI review must be a JSON object")
        usage = response.get("usage", {})
        input_details = usage.get("input_tokens_details", {})
        write_details = input_details.get("cache_write_tokens_details", {})
        return review, {
            "inputTokens": usage.get("input_tokens"),
            "outputTokens": usage.get("output_tokens"),
            "cachedInputTokens": input_details.get("cached_tokens", 0),
            "cacheWriteInputTokens": input_details.get("cache_write_tokens", 0),
            "cacheWrite30mInputTokens": write_details.get("ttl_30m_tokens", 0),
            "cacheWrite12hInputTokens": write_details.get("ttl_12h_tokens", 0),
        }

def _output_text(response):
    texts = []
    for output in response.get("output", []):
        if not isinstance(output, dict):
            continue
        for content in output.get("content", []):
            if isinstance(content, dict) and content.get("type") == "output_text":
                text = content.get("text")
                if isinstance(text, str):
                    texts.append(text)
    if not texts:
        raise OpenAIError("OpenAI response has no output_text")
    return "".join(texts)

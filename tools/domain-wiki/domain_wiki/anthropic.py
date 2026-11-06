import json

from .openai import MAX_MODEL_INPUT_CHARACTERS, UrlLibTransport


class AnthropicError(ValueError):
    pass


class AnthropicClient:
    provider = "anthropic"

    def __init__(
        self,
        api_key,
        *,
        model="claude-sonnet-5-5",
        reasoning_effort="medium",
        max_input_characters=MAX_MODEL_INPUT_CHARACTERS,
        max_output_tokens=8_000,
        transport=None,
    ):
        if not api_key:
            raise AnthropicError("ANTHROPIC_API_KEY is required")
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
            raise AnthropicError(
                "model input is too large: "
                f"{input_characters} > {self.max_input_characters} characters"
            )
        request_body = {
            "model": self.model,
            "max_tokens": self.max_output_tokens,
            "system": instructions,
            "messages": [{"role": "user", "content": serialized}],
            "output_config": {
                "effort": self.reasoning_effort,
                "format": {
                    "type": "json_schema",
                    "schema": _anthropic_schema(schema),
                },
            },
        }
        status, response = self.transport.request(
            "POST",
            "https://api.anthropic.com/v1/messages",
            {
                "x-api-key": self.api_key,
                "anthropic-version": "2023-06-01",
                "Content-Type": "application/json",
            },
            json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
        )
        if status < 200 or status >= 300:
            message = response.get("error", {}).get("message", "unknown error")
            raise AnthropicError(f"Anthropic API {status}: {message}")
        if response.get("stop_reason") == "max_tokens":
            raise AnthropicError("Anthropic response incomplete: max_tokens")
        output_text = _output_text(response)
        try:
            review = json.loads(output_text)
        except json.JSONDecodeError as error:
            raise AnthropicError("Anthropic response is not valid JSON") from error
        if not isinstance(review, dict):
            raise AnthropicError("Anthropic review must be a JSON object")
        usage = response.get("usage", {})
        return review, {
            "inputTokens": usage.get("input_tokens"),
            "outputTokens": usage.get("output_tokens"),
            "cachedInputTokens": usage.get("cache_read_input_tokens", 0),
            "cacheWriteInputTokens": usage.get("cache_creation_input_tokens", 0),
            "servedModel": response.get("model"),
        }


def _anthropic_schema(value):
    if isinstance(value, dict):
        unsupported = {
            "$schema", "$id", "title", "minimum", "maximum", "multipleOf", "pattern"
        }
        return {
            key: _anthropic_schema(item)
            for key, item in value.items()
            if key not in unsupported
        }
    if isinstance(value, list):
        return [_anthropic_schema(item) for item in value]
    return value


def _output_text(response):
    texts = [
        block.get("text")
        for block in response.get("content", [])
        if isinstance(block, dict)
        and block.get("type") == "text"
        and isinstance(block.get("text"), str)
    ]
    if not texts:
        raise AnthropicError("Anthropic response has no text content")
    return "".join(texts)

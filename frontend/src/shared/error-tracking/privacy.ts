const FILTERED = "[Filtered]";
const SECRET_KEY = /cookie|authorization|password|passwd|secret|token|apikey|servicekey|sessionid/i;
const SECRET_NAME =
  "(?:set[-_]?cookie|cookie|(?:proxy[-_]?)?authorization|(?:access|refresh|id|csrf|auth)[-_]?token|token|password|passwd|secret|api[-_]?key|service[-_]?key|(?:client)?session[-_]?id)";
const SECRET_ASSIGNMENT = new RegExp(
  "(\\b" + SECRET_NAME + "[\"']?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"?|'(?:\\\\.|[^'\\\\])*'?|[^\\s<>&;,}]+)",
  "gi",
);

export function redactedTextFrom(text: string): string {
  return text
    .replace(/&(?:quot|#34|#x22);/gi, '"')
    .replace(/&(?:apos|#39|#x27);/gi, "'")
    .replace(/(^|\n)(\s*(?:set-cookie|cookie|(?:proxy-)?authorization)\s*:\s*)[^\r\n]*/gi, "$1$2[Filtered]")
    .replace(SECRET_ASSIGNMENT, (_match, prefix: string, value: string) => {
      const quote = value.startsWith('"') ? '"' : value.startsWith("'") ? "'" : "";
      return prefix + quote + FILTERED + quote;
    })
    .replace(/\b(Bearer|Basic)\s+[A-Za-z0-9+/_.=~-]+/gi, "$1 [Filtered]")
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, FILTERED)
    .replace(/<(?:input|meta)\b[^>]*>/gi, (tag) => (SECRET_KEY.test(tag) ? FILTERED : tag))
    .replace(/https?:\/\/[^\s"'<>]+/gi, (url) => safeUrlFrom(url));
}

export function safeUrlFrom(value: string): string {
  try {
    const url = new URL(value, "https://relative.invalid");
    const origin = /^https?:\/\//i.test(value) ? url.origin : "";
    return origin + url.pathname;
  } catch {
    return "[Invalid URL]";
  }
}

export function requestPathFrom(value: string): string {
  try {
    return new URL(value, "https://relative.invalid").pathname;
  } catch {
    return "[Invalid URL]";
  }
}

export function redactedValueFrom<T>(value: T): T {
  const seen = new WeakSet<object>();
  function toRedactedNode(item: unknown): unknown {
    if (typeof item === "string") return redactedTextFrom(item);
    if (item === null || typeof item !== "object") return item;
    if (seen.has(item)) return "[Circular]";
    seen.add(item);
    const result = Array.isArray(item)
      ? item.map(toRedactedNode)
      : Object.fromEntries(
          Object.entries(item).map(([key, child]) => [
            key,
            SECRET_KEY.test(key.replace(/[^a-z0-9]/gi, "")) ? FILTERED : toRedactedNode(child),
          ]),
        );
    seen.delete(item);
    return result;
  }
  return toRedactedNode(value) as T;
}

export function redactedBodyFrom(body: string): string {
  try {
    return JSON.stringify(redactedValueFrom(JSON.parse(body)));
  } catch {
    return redactedTextFrom(body);
  }
}

export function boardReturnTo(candidate: string): string {
  if (candidate === "/") return candidate;
  const [path, query] = candidate.split("?");
  if (!path || !/^\/routes\/\d{9}$/.test(path)) return "/";
  if (query === undefined) return path;
  const parameters = query.split("&");
  const names = parameters.map((parameter) => parameter.split("=")[0]);
  return new Set(names).size === parameters.length &&
    parameters.every((parameter) => /^(direction=(UP|DOWN)|stopId=[A-Za-z0-9_-]{1,40})$/.test(parameter)) &&
    candidate.split("?").length === 2
    ? candidate
    : "/";
}

export function localReturnTo(candidate: string | null): string {
  if (candidate === "/mypage") return candidate;
  if (candidate && /^\/\?q=\d{1,20}$/.test(candidate)) return candidate;
  return boardReturnTo(candidate ?? "/");
}

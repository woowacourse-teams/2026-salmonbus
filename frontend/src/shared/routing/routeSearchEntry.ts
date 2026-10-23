// 키보드로 홈 검색바에 들어와 바로 입력하면 첫 글자가 홈 입력창에 들어온다. 검색 화면은 그 글자로 시작한다.
export function routeSearchStateFor(input: string) {
  return { input };
}

export function initialInputFrom(state: unknown): string {
  if (typeof state !== "object" || state === null || !("input" in state)) return "";
  return typeof state.input === "string" ? state.input : "";
}

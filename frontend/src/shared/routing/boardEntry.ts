export type BoardEntry = "search" | "favorite" | "direct";

export function boardEntryStateFor(entry: Exclude<BoardEntry, "direct">) {
  return { boardEntry: entry };
}

// 이동 state가 없으면 공유 링크, 주소 입력, 로그인 복귀, 앱 진입처럼 앱 화면을 거치지 않고 연 판정 보드다.
export function boardEntryFrom(state: unknown): BoardEntry {
  if (typeof state !== "object" || state === null || !("boardEntry" in state)) return "direct";
  return state.boardEntry === "search" || state.boardEntry === "favorite" ? state.boardEntry : "direct";
}

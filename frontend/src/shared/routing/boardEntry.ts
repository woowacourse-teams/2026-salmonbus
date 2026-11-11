const NAVIGATED_BOARD_ENTRIES = ["search", "favorite", "whats_new", "notification"] as const;

export type BoardEntry = (typeof NAVIGATED_BOARD_ENTRIES)[number] | "direct";
export type StationSheetEntry = "summary" | "detail" | "full";

export function mapFlowFrom(state: unknown): boolean {
  return typeof state === "object" && state !== null && "mapFlow" in state && state.mapFlow === true;
}

export function quietMapLoadingFrom(state: unknown): boolean {
  return typeof state === "object" && state !== null && "quietMapLoading" in state && state.quietMapLoading === true;
}

export function stationSheetFrom(state: unknown): StationSheetEntry | null {
  if (typeof state !== "object" || state === null || !("stationSheet" in state)) return null;
  const snap = state.stationSheet;
  return snap === "summary" || snap === "detail" || snap === "full" ? snap : null;
}

export function boardEntryStateFor(entry: Exclude<BoardEntry, "direct">) {
  return { boardEntry: entry };
}

export function boardEntryFrom(state: unknown): BoardEntry {
  if (typeof state !== "object" || state === null || !("boardEntry" in state)) return "direct";
  return NAVIGATED_BOARD_ENTRIES.find((entry) => entry === state.boardEntry) ?? "direct";
}

const NAVIGATED_BOARD_ENTRIES = ["search", "favorite", "whats_new", "notification"] as const;

export type BoardEntry = (typeof NAVIGATED_BOARD_ENTRIES)[number] | "direct";

export function boardEntryStateFor(entry: Exclude<BoardEntry, "direct">) {
  return { boardEntry: entry };
}

export function boardEntryFrom(state: unknown): BoardEntry {
  if (typeof state !== "object" || state === null || !("boardEntry" in state)) return "direct";
  return NAVIGATED_BOARD_ENTRIES.find((entry) => entry === state.boardEntry) ?? "direct";
}

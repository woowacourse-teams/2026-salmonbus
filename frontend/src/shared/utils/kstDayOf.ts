const KST_OFFSET_MS = 9 * 60 * 60_000;

export function kstDayOf(now: number): string {
  return new Date(now + KST_OFFSET_MS).toISOString().slice(0, 10);
}

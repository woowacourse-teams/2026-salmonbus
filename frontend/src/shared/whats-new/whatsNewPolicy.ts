import type { WhatsNewNote } from "./whatsNewNotes";

export interface WhatsNewRecord {
  lastSeenId: number;
  autoShow: boolean;
  lastShownDay: string | null;
}

export type AutoShowChoice = "keep" | "stop";

const AUTO_SHOW_PERIOD_MS = 7 * 24 * 60 * 60_000;
const KST_OFFSET_MS = 9 * 60 * 60_000;

export function firstRecordFor(notes: readonly WhatsNewNote[], hasVisitedBefore: boolean): WhatsNewRecord {
  return { lastSeenId: hasVisitedBefore ? 0 : latestIdOf(notes), autoShow: true, lastShownDay: null };
}

export function notesToShowFor(
  notes: readonly WhatsNewNote[],
  record: WhatsNewRecord,
  now: number,
): readonly WhatsNewNote[] {
  if (!record.autoShow || record.lastShownDay === kstDayOf(now)) return [];
  return notes
    .filter((note) => note.id > record.lastSeenId && now < autoShowEndOf(note))
    .sort((left, right) => right.id - left.id);
}

export function shownRecordFor(record: WhatsNewRecord, now: number): WhatsNewRecord {
  return { ...record, lastShownDay: kstDayOf(now) };
}

export function closedRecordFor(
  record: WhatsNewRecord,
  shownNotes: readonly WhatsNewNote[],
  autoShow: AutoShowChoice,
): WhatsNewRecord {
  return {
    ...record,
    lastSeenId: Math.max(record.lastSeenId, latestIdOf(shownNotes)),
    autoShow: autoShow === "stop" ? false : record.autoShow,
  };
}

function latestIdOf(notes: readonly WhatsNewNote[]): number {
  return notes.reduce((latest, note) => Math.max(latest, note.id), 0);
}

function autoShowEndOf(note: WhatsNewNote): number {
  return Date.parse(`${note.publishedAt}T00:00:00+09:00`) + AUTO_SHOW_PERIOD_MS;
}

function kstDayOf(now: number): string {
  return new Date(now + KST_OFFSET_MS).toISOString().slice(0, 10);
}

import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { listedRoutesFor, type FirstSeenTimes, type ListedRoute } from "@/shared/route-list/newRoutePolicy";
import type { WhatsNewNote } from "./whatsNewNotes";

export interface WhatsNewRecord {
  lastSeenId: number;
  lastShownDay: string | null;
  routesSeenUntil: number;
}

export type WhatsNewCloseMethod = "closeButton" | "backdrop" | "escape" | "routeRow";

const AUTO_SHOW_PERIOD_MS = 7 * 24 * 60 * 60_000;
const KST_OFFSET_MS = 9 * 60 * 60_000;

export function firstRecordFor(notes: readonly WhatsNewNote[], hasVisitedBefore: boolean, now: number): WhatsNewRecord {
  return {
    lastSeenId: hasVisitedBefore ? 0 : latestIdOf(notes),
    lastShownDay: null,
    routesSeenUntil: firstRoutesSeenUntilFor(now),
  };
}

// 노선 목록은 기록을 만든 뒤에 도착한다. 기록을 처음 만든 날 끝까지 처음 본 노선은 본 것으로 쳐서,
// 새로 추가된 노선 칸에는 이 기록이 생긴 뒤에 서비스 중이 된 노선만 나오게 한다.
export function firstRoutesSeenUntilFor(now: number): number {
  return Date.parse(`${kstDayOf(now)}T23:59:59.999+09:00`);
}

export function notesToShowFor(
  notes: readonly WhatsNewNote[],
  record: WhatsNewRecord,
  now: number,
): readonly WhatsNewNote[] {
  if (isShownToday(record, now)) return [];
  return notes
    .filter((note) => note.id > record.lastSeenId && now < autoShowEndOf(note))
    .sort((left, right) => right.id - left.id);
}

export function newRoutesToShowFor(
  routes: readonly RouteSummary[],
  firstSeenTimes: FirstSeenTimes,
  record: WhatsNewRecord,
  now: number,
): ListedRoute[] {
  if (isShownToday(record, now)) return [];
  return listedRoutesFor(routes, firstSeenTimes, now).filter(
    (route) => route.isNew && (firstSeenTimes.get(route.id) ?? 0) > record.routesSeenUntil,
  );
}

export function shownRecordFor(record: WhatsNewRecord, now: number): WhatsNewRecord {
  return { ...record, lastShownDay: kstDayOf(now) };
}

export function closedRecordFor(record: WhatsNewRecord, shownNotes: readonly WhatsNewNote[]): WhatsNewRecord {
  return { ...record, lastSeenId: Math.max(record.lastSeenId, latestIdOf(shownNotes)) };
}

export function routesSeenRecordFor(record: WhatsNewRecord, checkedAt: number): WhatsNewRecord {
  return { ...record, routesSeenUntil: Math.max(record.routesSeenUntil, checkedAt) };
}

function isShownToday(record: WhatsNewRecord, now: number): boolean {
  return record.lastShownDay === kstDayOf(now);
}

export function latestIdOf(notes: readonly WhatsNewNote[]): number {
  return notes.reduce((latest, note) => Math.max(latest, note.id), 0);
}

function autoShowEndOf(note: WhatsNewNote): number {
  return Date.parse(`${note.publishedAt}T00:00:00+09:00`) + AUTO_SHOW_PERIOD_MS;
}

function kstDayOf(now: number): string {
  return new Date(now + KST_OFFSET_MS).toISOString().slice(0, 10);
}

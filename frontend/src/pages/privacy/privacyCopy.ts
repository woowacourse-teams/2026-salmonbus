import type { EditionSummary, EffectiveDate } from "./editionPolicy";

export function effectiveDateLabelOf(date: EffectiveDate): string {
  const [year, month, day] = date.split("-").map(Number);
  return `${year}년 ${month}월 ${day}일`;
}

export function editionPeriodLabelOf(summary: EditionSummary): string {
  const start = effectiveDateLabelOf(summary.effectiveDate);
  if (summary.isUpcoming) return `${start}부터 적용 예정`;
  if (summary.lastDay !== null) return `${start}부터 ${effectiveDateLabelOf(summary.lastDay)}까지 적용`;
  return `${start}부터 적용`;
}

import type { WhatsNewNote } from "./whatsNewNotes";

export function noteHeadingLabelOf(note: WhatsNewNote): string {
  const [, month, day] = note.publishedAt.split("-");
  return `${Number(month)}월 ${Number(day)}일 업데이트`;
}

export function moreRoutesLabelOf(count: number): string {
  return `노선 ${count}개가 더 추가됐어요`;
}

import type { WhatsNewNote } from "./whatsNewNotes";

export function noteHeadingLabelOf(note: WhatsNewNote): string {
  const [, month, day] = note.publishedAt.split("-");
  return `${Number(month)}월 ${Number(day)}일 업데이트`;
}

import type { WhatsNewNote } from "./whatsNewNotes";

export function noteHeadingLabelOf(note: WhatsNewNote): string {
  const [, month, day] = note.publishedAt.split("-");
  return `v${note.version} / ${Number(month)}월 ${Number(day)}일`;
}

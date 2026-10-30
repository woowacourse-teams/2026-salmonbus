import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { readLoginReturn } from "@/shared/routing/loginReturn";
import { WHATS_NEW_NOTES, type WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { notesToShowFor } from "@/shared/whats-new/whatsNewPolicy";
import { loadWhatsNewRecord, recordWhatsNewClosed, recordWhatsNewShown } from "@/shared/whats-new/whatsNewStorage";
import { WhatsNewDialog } from "./WhatsNewDialog";

function notesToShowOnEntry(deferred: boolean): readonly WhatsNewNote[] {
  const record = loadWhatsNewRecord(WHATS_NEW_NOTES);
  if (record === null || deferred) return [];
  return notesToShowFor(WHATS_NEW_NOTES, record, Date.now());
}

export function WhatsNewOnEntry() {
  const location = useLocation();
  // 로그인 복귀 중이면 곧 원래 화면으로 이동하고, 안내가 있으면 토스트와 겹쳐서 다음 홈 진입으로 미룬다.
  const [notes, setNotes] = useState(() =>
    notesToShowOnEntry(readLoginReturn() !== null || typeof location.state?.notice === "string"),
  );

  useEffect(() => {
    if (notes.length > 0) recordWhatsNewShown(Date.now());
  }, [notes]);

  const close = () => {
    recordWhatsNewClosed(notes);
    setNotes([]);
  };

  return notes.length > 0 ? <WhatsNewDialog notes={notes} onClose={close} /> : null;
}

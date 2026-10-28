import { useEffect, useState } from "react";
import { Notice } from "@/shared/components/Notice";
import { WHATS_NEW_NOTES, type WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { notesToShowFor, type AutoShowChoice } from "@/shared/whats-new/whatsNewPolicy";
import { loadWhatsNewRecord, recordWhatsNewClosed, recordWhatsNewShown } from "@/shared/whats-new/whatsNewStorage";
import { WhatsNewDialog } from "./WhatsNewDialog";

function notesToShowOnEntry(): readonly WhatsNewNote[] {
  const record = loadWhatsNewRecord(WHATS_NEW_NOTES);
  return record === null ? [] : notesToShowFor(WHATS_NEW_NOTES, record, Date.now());
}

export function WhatsNewOnEntry() {
  const [notes, setNotes] = useState(notesToShowOnEntry);
  const [stoppedNotice, setStoppedNotice] = useState(false);

  useEffect(() => {
    if (notes.length > 0) recordWhatsNewShown(Date.now());
  }, [notes]);

  const close = (autoShow: AutoShowChoice) => {
    recordWhatsNewClosed(notes, autoShow);
    setNotes([]);
    setStoppedNotice(autoShow === "stop");
  };

  return (
    <>
      {notes.length > 0 && <WhatsNewDialog notes={notes} onClose={close} />}
      {stoppedNotice && <Notice message="새 소식은 마이페이지에서 볼 수 있어요" />}
    </>
  );
}

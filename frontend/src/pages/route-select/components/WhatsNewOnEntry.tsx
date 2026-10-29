import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { Notice } from "@/shared/components/Notice";
import { readLoginReturn } from "@/shared/routing/loginReturn";
import { WHATS_NEW_NOTES, type WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { notesToShowFor, type AutoShowChoice } from "@/shared/whats-new/whatsNewPolicy";
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

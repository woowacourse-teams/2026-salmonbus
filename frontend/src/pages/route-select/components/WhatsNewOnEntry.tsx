import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router";
import { track } from "@/shared/analytics/track";
import { readLoginReturn } from "@/shared/routing/loginReturn";
import { WHATS_NEW_NOTES, type WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { notesToShowFor, type WhatsNewCloseMethod } from "@/shared/whats-new/whatsNewPolicy";
import { loadWhatsNewRecord, recordWhatsNewClosed, recordWhatsNewShown } from "@/shared/whats-new/whatsNewStorage";
import { whatsNewClosedPropertiesOf, whatsNewViewedPropertiesOf } from "../analytics/whatsNewEventPolicy";
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
  const shownAt = useRef<number | null>(null);

  useEffect(() => {
    // 개발 모드 StrictMode는 Effect를 두 번 돌려서, 막지 않으면 노출 이벤트가 두 번 간다.
    if (notes.length === 0 || shownAt.current !== null) return;
    shownAt.current = performance.now();
    recordWhatsNewShown(Date.now());
    track("whats_new_viewed", whatsNewViewedPropertiesOf(notes));
  }, [notes]);

  const close = (closeMethod: WhatsNewCloseMethod) => {
    recordWhatsNewClosed(notes);
    const closedAt = performance.now();
    track("whats_new_closed", whatsNewClosedPropertiesOf(notes, closeMethod, closedAt - (shownAt.current ?? closedAt)));
    setNotes([]);
  };

  return notes.length > 0 ? <WhatsNewDialog notes={notes} onClose={close} /> : null;
}

import { useEffect, useEffectEvent, useRef } from "react";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import * as buttonStyles from "@/shared/styles/button.css";
import { noteHeadingLabelOf } from "@/shared/whats-new/whatsNewCopy";
import type { WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import type { AutoShowChoice } from "@/shared/whats-new/whatsNewPolicy";
import * as styles from "./WhatsNewDialog.css";

interface WhatsNewDialogProps {
  notes: readonly WhatsNewNote[];
  onClose: (autoShow: AutoShowChoice) => void;
}

export function WhatsNewDialog({ notes, onClose }: WhatsNewDialogProps) {
  const notesList = useRef<HTMLDivElement>(null);
  const closeFromBackdrop = useEffectEvent(() => onClose("keep"));

  useEffect(() => {
    const dialog = notesList.current?.closest("dialog");
    if (!dialog) return;
    // 바깥을 누르면 dialog 요소가 이벤트를 받는다. 안쪽 여백과 구분하려고 좌표를 보고,
    // 안에서 시작한 드래그가 바깥에서 끝날 때는 닫지 않으려고 누른 위치도 본다.
    const isOutside = (event: MouseEvent) => {
      if (event.target !== dialog) return false;
      const box = dialog.getBoundingClientRect();
      return (
        event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom
      );
    };
    let pressedOutside = false;
    const rememberPress = (event: PointerEvent) => {
      pressedOutside = isOutside(event);
    };
    const closeWhenOutside = (event: MouseEvent) => {
      if (pressedOutside && isOutside(event)) closeFromBackdrop();
    };
    dialog.addEventListener("pointerdown", rememberPress);
    dialog.addEventListener("click", closeWhenOutside);
    return () => {
      dialog.removeEventListener("pointerdown", rememberPress);
      dialog.removeEventListener("click", closeWhenOutside);
    };
  }, []);

  return (
    <ConfirmDialog
      title="새 소식이 있어요"
      className={styles.dialog}
      titleClassName={styles.title}
      onCancel={() => onClose("keep")}
    >
      <div ref={notesList} className={styles.notes}>
        {notes.map((note) => (
          <section key={note.id} aria-labelledby={`whats-new-${note.id}`}>
            <h3 id={`whats-new-${note.id}`} className={styles.noteDate}>
              {noteHeadingLabelOf(note)}
            </h3>
            <p className={styles.sectionTitle}>새로 추가된 기능</p>
            <ul className={styles.features}>
              {note.features.map((feature) => (
                <li key={feature}>{feature}</li>
              ))}
            </ul>
          </section>
        ))}
      </div>
      <div className={styles.actions}>
        <button className={buttonStyles.primary} type="button" onClick={() => onClose("keep")}>
          닫기
        </button>
        <button className={`${buttonStyles.text} ${styles.stopButton}`} type="button" onClick={() => onClose("stop")}>
          다시 보지 않기
        </button>
      </div>
    </ConfirmDialog>
  );
}

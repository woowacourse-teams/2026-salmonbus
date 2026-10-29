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
  return (
    <ConfirmDialog
      title="새 소식이 있어요"
      className={styles.dialog}
      titleClassName={styles.title}
      onCancel={() => onClose("keep")}
    >
      <div className={styles.notes}>
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

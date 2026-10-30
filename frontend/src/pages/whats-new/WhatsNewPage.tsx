import { useEffect } from "react";
import { Link } from "react-router";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { paths } from "@/shared/routing/paths";
import { noteHeadingLabelOf } from "@/shared/whats-new/whatsNewCopy";
import { WHATS_NEW_NOTES } from "@/shared/whats-new/whatsNewNotes";
import { recordWhatsNewListOpened } from "@/shared/whats-new/whatsNewStorage";
import * as styles from "./WhatsNewPage.css";

export function WhatsNewPage() {
  useEffect(() => {
    recordWhatsNewListOpened(WHATS_NEW_NOTES);
  }, []);

  return (
    <AccountLayout contentClassName={styles.page}>
      <div className={styles.backTitle}>
        <Link className={styles.backIcon} to={paths.mypage} aria-label="마이페이지로 돌아가기">
          <svg
            width="17"
            height="17"
            viewBox="0 0 17 17"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M13.6 8.5H3.4M7.9 4 3.4 8.5 7.9 13" />
          </svg>
        </Link>
        <h1 className={styles.title}>새 소식</h1>
      </div>
      {WHATS_NEW_NOTES.length === 0 ? (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>아직 새 소식이 없어요</p>
          <p className={styles.caption}>새 기능이 나오면 여기에서 알려 드릴게요</p>
        </div>
      ) : (
        <div className={styles.notes}>
          {WHATS_NEW_NOTES.map((note) => (
            <section key={note.id} className={styles.note} aria-labelledby={`whats-new-note-${note.id}`}>
              <h2 id={`whats-new-note-${note.id}`} className={styles.noteDate}>
                {noteHeadingLabelOf(note)}
              </h2>
              <ul className={styles.features}>
                {note.features.map((feature) => (
                  <li key={feature}>{feature}</li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </AccountLayout>
  );
}

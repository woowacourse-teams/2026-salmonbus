import { useEffect, useEffectEvent, useRef } from "react";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { RouteRow } from "@/shared/components/RouteRow";
import type { ListedRoute } from "@/shared/route-list/newRoutePolicy";
import * as buttonStyles from "@/shared/styles/button.css";
import { moreRoutesLabelOf, noteHeadingLabelOf } from "@/shared/whats-new/whatsNewCopy";
import type { WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import type { WhatsNewCloseMethod } from "@/shared/whats-new/whatsNewPolicy";
import * as styles from "./WhatsNewDialog.css";

const MAX_SHOWN_ROUTES = 5;

interface WhatsNewDialogProps {
  notes: readonly WhatsNewNote[];
  routes: readonly ListedRoute[];
  onClose: (closeMethod: WhatsNewCloseMethod) => void;
  onSelectRoute: (route: ListedRoute) => void;
}

export function WhatsNewDialog({ notes, routes, onClose, onSelectRoute }: WhatsNewDialogProps) {
  const notesList = useRef<HTMLDivElement>(null);
  const closeFromBackdrop = useEffectEvent(() => onClose("backdrop"));

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
      onCancel={() => onClose("escape")}
    >
      <div ref={notesList} className={styles.notes}>
        {routes.length > 0 && (
          <section aria-labelledby="whats-new-routes">
            <h3 id="whats-new-routes" className={styles.routesTitle}>
              새로 추가된 노선
            </h3>
            <ul className={styles.routes}>
              {routes.slice(0, MAX_SHOWN_ROUTES).map((route) => (
                <li key={route.id}>
                  <RouteRow route={route} onSelect={() => onSelectRoute(route)} />
                </li>
              ))}
            </ul>
            {routes.length > MAX_SHOWN_ROUTES && (
              <p className={styles.moreRoutes}>{moreRoutesLabelOf(routes.length - MAX_SHOWN_ROUTES)}</p>
            )}
          </section>
        )}
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
        <button className={buttonStyles.primary} type="button" onClick={() => onClose("closeButton")}>
          닫기
        </button>
      </div>
    </ConfirmDialog>
  );
}

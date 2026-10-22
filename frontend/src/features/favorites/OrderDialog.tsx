import { useRef, useState, type ReactNode } from "react";
import { saveFavoriteOrder, saveGroupOrder } from "@/shared/api/favorites.api";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import * as buttons from "@/shared/styles/button.css";
import { useFavorites, useHomeFavorites } from "./FavoritesContext";
import { FavoritesNotice } from "./FavoritesNotice";
import { moveItem } from "./favoritePolicy";
import { useFavoriteAction } from "./useFavoriteAction";
import * as styles from "./favorites.css";

export function OrderDialog({ onClose }: { onClose: () => void }) {
  const { groups, reload } = useFavorites();
  const [saved, setSaved] = useState(groups);
  const [draft, setDraft] = useState(groups);
  const [partial, setPartial] = useState(false);
  const [revision, retry] = useState(0);
  const summaries = useHomeFavorites("all", revision);
  const favorites = latestSuccessOf(summaries)?.body.groups.flatMap((group) => group.favorites) ?? [];
  const { busy, error, run } = useFavoriteAction();
  const idsOf = (items: { id: string }[]) => items.map((item) => item.id);
  const changed = JSON.stringify(draft) !== JSON.stringify(saved);
  const close = () => {
    if (busy) return;
    if (partial) reload();
    onClose();
  };
  async function save() {
    const changes: { groupId: string | null; ids: string[] }[] = [];
    if (JSON.stringify(idsOf(draft)) !== JSON.stringify(idsOf(saved)))
      changes.push({ groupId: null, ids: idsOf(draft) });
    for (const group of draft) {
      const before = saved.find((candidate) => candidate.id === group.id);
      if (JSON.stringify(group.favorites) !== JSON.stringify(before?.favorites))
        changes.push({ groupId: group.id, ids: idsOf(group.favorites) });
    }
    if (changes.length === 0) return;
    const ok = await run(async () => {
      let completed = 0;
      const persist = async (change: (typeof changes)[number]) => {
        const result = await (change.groupId === null
          ? saveGroupOrder(change.ids)
          : saveFavoriteOrder(change.groupId, change.ids));
        if (result.ok) {
          completed += 1;
          setSaved((current) =>
            change.groupId === null
              ? change.ids.map((id) => current.find((group) => group.id === id)!)
              : current.map((group) =>
                  group.id === change.groupId
                    ? {
                        ...group,
                        favorites: change.ids.map((id) => group.favorites.find((favorite) => favorite.id === id)!),
                      }
                    : group,
                ),
          );
        } else if (completed > 0) setPartial(true);
        return result;
      };
      let result = await persist(changes[0]!);
      for (const change of changes.slice(1)) {
        if (!result.ok) return result;
        result = await persist(change);
      }
      return result;
    });
    if (ok) onClose();
  }
  return (
    <ConfirmDialog title="순서 편집" className={`${styles.fullEditor} amp-mask`} onCancel={close}>
      <p className={styles.subtle}>손잡이를 끌거나 선택 후 위·아래 방향키로 순서를 바꿀 수 있어요.</p>
      <h3 className={styles.heading}>그룹 순서</h3>
      <OrderList
        items={draft}
        disabled={busy}
        label={(group) => group.name}
        onMove={(from, to) => setDraft((current) => moveItem(current, from, to))}
      />
      <FavoritesNotice resource={summaries} onRetry={() => retry((value) => value + 1)} />
      {draft.map((group) => (
        <section key={group.id}>
          <h3 className={styles.heading}>{group.name} 항목 순서</h3>
          <OrderList
            items={group.favorites}
            disabled={busy}
            label={(favorite) => {
              const summary = favorites.find((candidate) => candidate.id === favorite.id);
              return summary
                ? `${summary.route.displayName} · ${summary.stop.name}`
                : `즐겨찾기 ${group.favorites.indexOf(favorite) + 1}`;
            }}
            onMove={(from, to) =>
              setDraft((current) =>
                current.map((candidate) =>
                  candidate.id === group.id
                    ? { ...candidate, favorites: moveItem(candidate.favorites, from, to) }
                    : candidate,
                ),
              )
            }
          />
          {group.favorites.length === 0 && <p className={styles.subtle}>아직 등록한 즐겨찾기가 없어요.</p>}
        </section>
      ))}
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      {partial && (
        <p className={styles.subtle} role="status">
          일부 순서는 저장됐어요. 아직 저장하지 못한 순서만 다시 저장할 수 있어요.
        </p>
      )}
      <div className={styles.actions}>
        <button className={buttons.primary} disabled={busy || !changed} onClick={() => void save()}>
          {busy ? "저장 중…" : "완료"}
        </button>
        <button className={buttons.secondary} disabled={busy} onClick={close}>
          {partial ? "닫기" : "취소"}
        </button>
      </div>
    </ConfirmDialog>
  );
}

function OrderList<T extends { id: string }>({
  items,
  disabled,
  label,
  onMove,
}: {
  items: T[];
  disabled: boolean;
  label: (item: T) => ReactNode;
  onMove: (from: number, to: number) => void;
}) {
  const dragging = useRef<string | null>(null);
  return (
    <ul className={styles.list}>
      {items.map((item, index) => (
        <li className={styles.sortItem} key={item.id} data-sort-id={item.id}>
          <span>{label(item)}</span>
          <button
            type="button"
            className={styles.grip}
            disabled={disabled || items.length < 2}
            aria-label={`${typeof label(item) === "string" ? label(item) : index + 1} 순서 변경`}
            onKeyDown={(event) => {
              if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
              event.preventDefault();
              onMove(index, index + (event.key === "ArrowUp" ? -1 : 1));
            }}
            onPointerDown={(event) => {
              dragging.current = item.id;
              event.currentTarget.setPointerCapture(event.pointerId);
            }}
            onPointerUp={() => {
              dragging.current = null;
            }}
            onPointerCancel={() => {
              dragging.current = null;
            }}
            onPointerMove={(event) => {
              if (dragging.current !== item.id) return;
              const rows = event.currentTarget.closest("ul")?.querySelectorAll<HTMLElement>("[data-sort-id]");
              if (!rows) return;
              rows.forEach((row, targetIndex) => {
                const box = row.getBoundingClientRect();
                if (event.clientY >= box.top && event.clientY <= box.bottom) onMove(index, targetIndex);
              });
            }}
          >
            ☰
          </button>
        </li>
      ))}
    </ul>
  );
}

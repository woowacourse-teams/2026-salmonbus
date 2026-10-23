import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import * as styles from "./SortableList.css";

interface SortableItem {
  id: string;
}

interface SortableListProps<T extends SortableItem> {
  items: T[];
  horizontal?: boolean;
  disabled: boolean;
  label: (item: T) => string;
  render: (item: T, handle: ReactNode) => ReactNode;
  onMove: (from: number, to: number) => void;
}

interface PositionBox {
  start: number;
  size: number;
}
interface DragSession {
  pointerId: number;
  from: number;
  start: number;
  shift: number;
  boxes: PositionBox[];
}
interface DragState {
  from: number;
  to: number;
  offset: number;
  shift: number;
}

export function SortableList<T extends SortableItem>({
  items,
  horizontal = false,
  disabled,
  label,
  render,
  onMove,
}: SortableListProps<T>) {
  const list = useRef<HTMLUListElement>(null);
  const session = useRef<DragSession | null>(null);
  const [drag, setDrag] = useState<DragState | null>(null);
  const previous = useRef<Map<string, DOMRect> | null>(null);
  const [announcement, announce] = useState("");
  const rows = () => Array.from(list.current?.children ?? []).filter((row) => row instanceof HTMLLIElement);
  const remember = () => {
    const positions = new Map<string, DOMRect>();
    for (const row of rows()) {
      const id = row.dataset.sortId;
      if (id !== undefined) positions.set(id, row.getBoundingClientRect());
    }
    previous.current = positions;
  };

  useLayoutEffect(() => {
    const before = previous.current;
    previous.current = null;
    if (!before || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    for (const row of rows()) {
      // DOM 이동 후 남은 전환이 새 위치 측정에 섞이지 않도록 취소한다.
      row.getAnimations().forEach((animation) => animation.cancel());
      const id = row.dataset.sortId;
      const old = id === undefined ? undefined : before.get(id);
      const next = row.getBoundingClientRect();
      if (old)
        row.animate(
          [
            {
              transformOrigin: "top left",
              transform: `translate(${old.x - next.x}px, ${old.y - next.y}px) scale(${old.width / next.width}, ${old.height / next.height})`,
            },
            { transformOrigin: "top left", transform: "none" },
          ],
          { duration: 220, easing: "cubic-bezier(0.22, 1, 0.36, 1)" },
        );
    }
  }, [items, drag]);

  function finish(commit: boolean) {
    if (!session.current || !drag) return;
    remember();
    session.current = null;
    const item = items[drag.from];
    if (commit && drag.from !== drag.to && item !== undefined) {
      onMove(drag.from, drag.to);
      announce(`${label(item)}: ${drag.to + 1}번째로 이동했어요.`);
    }
    setDrag(null);
  }

  return (
    <>
      <ul
        ref={list}
        className={horizontal ? styles.groups : styles.cards}
        aria-label={horizontal ? "그룹 순서" : "즐겨찾기 순서"}
        data-sorting={drag !== null}
      >
        {items.map((item, index) => {
          let offset = 0;
          if (drag) {
            if (index === drag.from) offset = drag.offset;
            else if (index > drag.from && index <= drag.to) offset = -drag.shift;
            else if (index < drag.from && index >= drag.to) offset = drag.shift;
          }
          const lifted = drag?.from === index;
          return (
            <li
              key={item.id}
              className={horizontal ? styles.group : styles.card}
              data-sort-id={item.id}
              data-dragging={lifted}
              style={{ transform: `translate${horizontal ? "X" : "Y"}(${offset}px)${lifted ? " scale(1.025)" : ""}` }}
            >
              {render(
                item,
                <button
                  type="button"
                  className={horizontal ? styles.groupGrip : styles.grip}
                  disabled={disabled || items.length < 2}
                  aria-label={`${label(item)} 순서 변경`}
                  aria-describedby="favorite-sort-help"
                  onKeyDown={(event) => {
                    if (event.key === "Escape" && session.current) {
                      event.stopPropagation();
                      finish(false);
                      return;
                    }
                    const delta = horizontal
                      ? event.key === "ArrowLeft"
                        ? -1
                        : event.key === "ArrowRight"
                          ? 1
                          : 0
                      : event.key === "ArrowUp"
                        ? -1
                        : event.key === "ArrowDown"
                          ? 1
                          : 0;
                    if (!delta || session.current) return;
                    event.preventDefault();
                    const target = Math.max(0, Math.min(items.length - 1, index + delta));
                    if (target === index) return;
                    remember();
                    onMove(index, target);
                    announce(`${label(item)}: ${target + 1}번째로 이동했어요.`);
                  }}
                  onPointerDown={(event) => {
                    const element = list.current;
                    if (!event.isPrimary || event.button !== 0 || element === null) return;
                    event.preventDefault();
                    event.currentTarget.focus();
                    rows().forEach((row) =>
                      row.getAnimations({ subtree: true }).forEach((animation) => animation.cancel()),
                    );
                    const boxes = rows().map((row) => {
                      const box = row.getBoundingClientRect();
                      return { start: horizontal ? box.x : box.y, size: horizontal ? box.width : box.height };
                    });
                    const gap = parseFloat(getComputedStyle(element)[horizontal ? "columnGap" : "rowGap"]);
                    const origin = boxes[index];
                    if (origin === undefined || !Number.isFinite(gap)) return;
                    const shift = origin.size + gap;
                    session.current = {
                      pointerId: event.pointerId,
                      from: index,
                      start: horizontal ? event.clientX : event.clientY,
                      boxes,
                      shift,
                    };
                    setDrag({ from: index, to: index, offset: 0, shift });
                    event.currentTarget.setPointerCapture(event.pointerId);
                  }}
                  onPointerMove={(event) => {
                    const current = session.current;
                    if (!current || current.pointerId !== event.pointerId) return;
                    const origin = current.boxes[current.from];
                    const first = current.boxes[0];
                    const last = current.boxes.at(-1);
                    if (origin === undefined || first === undefined || last === undefined) return;
                    const offset = Math.max(
                      first.start - origin.start,
                      Math.min(
                        last.start + last.size - origin.start - origin.size,
                        (horizontal ? event.clientX : event.clientY) - current.start,
                      ),
                    );
                    const position = origin.start + offset;
                    let to = current.from;
                    let distance = Math.abs(offset);
                    for (const [i, box] of current.boxes.entries()) {
                      const slot = i > current.from ? box.start + box.size - origin.size : box.start;
                      if (Math.abs(position - slot) < distance) {
                        to = i;
                        distance = Math.abs(position - slot);
                      }
                    }
                    setDrag({ from: current.from, to, offset, shift: current.shift });
                  }}
                  onPointerUp={() => finish(true)}
                  onPointerCancel={() => finish(false)}
                  onLostPointerCapture={() => finish(false)}
                />,
              )}
            </li>
          );
        })}
      </ul>
      <span className={visuallyHidden} role="status">
        {announcement}
      </span>
    </>
  );
}

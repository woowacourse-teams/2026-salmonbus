import { useId, useLayoutEffect, useRef, useState, type PointerEvent, type ReactNode, type RefObject } from "react";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { playHaptic } from "@/shared/haptic/haptic";
import * as styles from "./SortableList.css";

interface SortableItem {
  id: string;
}

interface SortableListProps<T extends SortableItem> {
  ariaLabel?: string;
  itemClassName?: string;
  items: T[];
  horizontal?: boolean;
  disabled: boolean;
  label: (item: T) => string;
  render: (item: T, handle: ReactNode) => ReactNode;
  onMove: (from: number, to: number) => void;
  dropZone?: SortableDropZone<T>;
}

interface SortableDropZone<T> {
  target: RefObject<HTMLElement | null>;
  onDrag: (item: T | null, overTarget: boolean) => void;
  onDrop: (item: T) => void;
}

interface PositionBox {
  start: number;
  size: number;
}
interface DragSession {
  pointerId: number;
  from: number;
  to: number;
  start: number;
  crossStart: number;
  shift: number;
  boxes: PositionBox[];
}
interface DragState {
  from: number;
  to: number;
  offset: number;
  crossOffset: number;
  shift: number;
}

export function SortableList<T extends SortableItem>({
  items,
  horizontal = false,
  ariaLabel,
  itemClassName,
  disabled,
  label,
  render,
  onMove,
  dropZone,
}: SortableListProps<T>) {
  const list = useRef<HTMLUListElement>(null);
  const labelIdPrefix = useId();
  const session = useRef<DragSession | null>(null);
  const suppressClick = useRef(false);
  const [drag, setDrag] = useState<DragState | null>(null);
  const previous = useRef<Map<string, DOMRect> | null>(null);
  const focusAfterMove = useRef<HTMLElement | null>(null);
  const [announcement, announce] = useState("");
  const rows = () => Array.from(list.current?.children ?? []).filter((row) => row instanceof HTMLLIElement);
  const remember = () => {
    const focused = document.activeElement;
    if (focused instanceof HTMLElement && list.current?.contains(focused)) focusAfterMove.current = focused;
    const positions = new Map<string, DOMRect>();
    for (const row of rows()) {
      const id = row.dataset.sortId;
      if (id !== undefined) positions.set(id, row.getBoundingClientRect());
    }
    previous.current = positions;
  };

  useLayoutEffect(() => {
    focusAfterMove.current?.focus();
    focusAfterMove.current = null;
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

  function isOverDropZone(x: number, y: number): boolean {
    const bounds = dropZone?.target.current?.getBoundingClientRect();
    return bounds !== undefined && x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom;
  }

  function finish(commit: boolean, event?: PointerEvent<HTMLElement>) {
    if (!session.current) return;
    session.current = null;
    dropZone?.onDrag(null, false);
    suppressClick.current = horizontal && drag !== null;
    if (!drag) return;
    remember();
    const item = items[drag.from];
    if (commit && item !== undefined && dropZone && event && isOverDropZone(event.clientX, event.clientY)) {
      setDrag(null);
      dropZone.onDrop(item);
      return;
    }
    if (commit && drag.from !== drag.to && item !== undefined) {
      onMove(drag.from, drag.to);
      announce(`${label(item)}: ${drag.to + 1}번째로 이동했어요.`);
    }
    setDrag(null);
  }

  function startDrag(event: PointerEvent<HTMLElement>, index: number) {
    const element = list.current;
    if (disabled || items.length < 2 || !event.isPrimary || event.button !== 0 || element === null) return;
    event.preventDefault();
    const target = horizontal && event.target instanceof Element ? event.target.closest("button") : event.currentTarget;
    const capture = target instanceof HTMLElement ? target : event.currentTarget;
    capture.focus();
    suppressClick.current = false;
    rows().forEach((row) => row.getAnimations({ subtree: true }).forEach((animation) => animation.cancel()));
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
      to: index,
      start: horizontal ? event.clientX : event.clientY,
      crossStart: horizontal ? event.clientY : event.clientX,
      boxes,
      shift,
    };
    if (!horizontal || (dropZone && capture.hasAttribute("data-sort-handle"))) {
      setDrag({ from: index, to: index, offset: 0, crossOffset: 0, shift });
      const item = items[index];
      if (item !== undefined) dropZone?.onDrag(item, false);
      playHaptic("impact");
    }
    capture.setPointerCapture(event.pointerId);
  }

  function moveDrag(event: PointerEvent<HTMLElement>) {
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
    const crossOffset = dropZone ? (horizontal ? event.clientY : event.clientX) - current.crossStart : 0;
    if (horizontal && drag === null && Math.hypot(offset, crossOffset) < 4) return;
    if (drag === null) playHaptic("impact");
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
    if (to !== current.to) {
      current.to = to;
      playHaptic("selection");
    }
    setDrag({ from: current.from, to, offset, crossOffset, shift: current.shift });
    const item = items[current.from];
    if (item !== undefined) dropZone?.onDrag(item, isOverDropZone(event.clientX, event.clientY));
  }

  return (
    <>
      <ul
        ref={list}
        className={horizontal ? styles.groups : styles.cards}
        aria-label={ariaLabel ?? (horizontal ? "그룹 순서" : "즐겨찾기 순서")}
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
          const crossOffset = lifted ? drag.crossOffset : 0;
          return (
            <li
              key={item.id}
              className={`${styles.item} ${horizontal ? styles.group : (itemClassName ?? styles.card)}`}
              data-sort-id={item.id}
              data-dragging={lifted}
              style={{
                transform: `translate(${horizontal ? offset : crossOffset}px, ${horizontal ? crossOffset : offset}px)${lifted ? " scale(1.025)" : ""}`,
              }}
              onPointerDown={horizontal ? (event) => startDrag(event, index) : undefined}
              onPointerMove={horizontal ? moveDrag : undefined}
              onPointerUp={horizontal ? (event) => finish(true, event) : undefined}
              onPointerCancel={horizontal ? () => finish(false) : undefined}
              onLostPointerCapture={horizontal ? () => finish(false) : undefined}
              onClickCapture={
                horizontal
                  ? (event) => {
                      if (suppressClick.current && event.detail > 0) {
                        event.preventDefault();
                        event.stopPropagation();
                      }
                      suppressClick.current = false;
                    }
                  : undefined
              }
              onKeyDown={
                horizontal
                  ? (event) => {
                      if (event.key === "Escape" && session.current) {
                        event.stopPropagation();
                        finish(false);
                      }
                    }
                  : undefined
              }
            >
              {render(
                item,
                <button
                  type="button"
                  data-sort-handle=""
                  className={horizontal ? styles.groupGrip : styles.grip}
                  disabled={disabled || items.length < 2}
                  aria-labelledby={`${labelIdPrefix}${item.id}`}
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
                  onPointerDown={!horizontal ? (event) => startDrag(event, index) : undefined}
                  onPointerMove={!horizontal ? moveDrag : undefined}
                  onPointerUp={!horizontal ? (event) => finish(true, event) : undefined}
                  onPointerCancel={!horizontal ? () => finish(false) : undefined}
                  onLostPointerCapture={!horizontal ? () => finish(false) : undefined}
                >
                  {/* Session Replay는 속성 값을 가리지 못해서, 그룹 이름은 aria-label 대신 숨긴 글자로 둔다. */}
                  <span id={`${labelIdPrefix}${item.id}`} hidden>
                    {`${label(item)} 순서 변경`}
                  </span>
                </button>,
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

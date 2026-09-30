import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { vars } from "@/shared/styles/tokens.css";
import { nextLargerSnap, sheetPosition, type SheetLayout } from "./sheetPolicy";
import { stopSheetHeightTransition, transitionSheetHeight } from "./useSheetDrag";

const CLOSED: SheetLayout = { open: false, snap: "half" };

export function useChatSheet() {
  const [layout, setLayout] = useState<SheetLayout>(CLOSED);
  const position = sheetPosition(layout);
  const sheetRef = useRef<HTMLElement | null>(null);
  const launcherRef = useRef<HTMLButtonElement | null>(null);
  const positionRef = useRef(position);

  useLayoutEffect(() => {
    positionRef.current = position;
  }, [position]);

  const collapse = useCallback((restoreFocus: boolean) => {
    const sheet = sheetRef.current;
    if (sheet !== null) stopSheetHeightTransition(sheet);
    setLayout((current) => ({ ...current, open: false }));
    if (restoreFocus) window.setTimeout(() => launcherRef.current?.focus(), 0);
  }, []);

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || positionRef.current === "collapsed") return;
      collapse(true);
    };
    window.addEventListener("keydown", handleEscape);
    return () => window.removeEventListener("keydown", handleEscape);
  }, [collapse]);

  function open() {
    const sheet = sheetRef.current;
    if (sheet !== null) {
      stopSheetHeightTransition(sheet);
      sheet.style.removeProperty("height");
    }
    setLayout({ open: true, snap: "half" });
    window.setTimeout(() => sheetRef.current?.focus({ preventScroll: true }), 0);
  }

  function settle(next: SheetLayout) {
    setLayout(next);
  }

  function toggleSize(restoreFocus: boolean) {
    const larger = nextLargerSnap(layout.snap);
    if (!layout.open || larger === null) {
      collapse(restoreFocus);
      return;
    }
    const sheet = sheetRef.current;
    if (sheet !== null) transitionSheetHeight(sheet, vars.duration.spring);
    setLayout({ open: true, snap: larger });
  }

  return { position, snap: layout.snap, sheetRef, launcherRef, open, settle, collapse, toggleSize };
}

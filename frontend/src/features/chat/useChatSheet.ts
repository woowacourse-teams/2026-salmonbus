import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { SheetPosition } from "./sheetPolicy";

export function useChatSheet(isDesktop: boolean) {
  const [position, setPosition] = useState<SheetPosition>("collapsed");
  const sheetRef = useRef<HTMLElement | null>(null);
  const launcherRef = useRef<HTMLButtonElement | null>(null);
  const positionRef = useRef(position);

  useLayoutEffect(() => {
    positionRef.current = position;
  }, [position]);

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || positionRef.current === "collapsed") return;
      setPosition("collapsed");
      window.setTimeout(() => launcherRef.current?.focus(), 0);
    };
    window.addEventListener("keydown", handleEscape);
    return () => window.removeEventListener("keydown", handleEscape);
  }, []);

  function open() {
    setPosition("half");
    window.setTimeout(() => sheetRef.current?.focus({ preventScroll: true }), 0);
  }

  function move(next: SheetPosition) {
    setPosition(next);
    if (next === "collapsed") window.setTimeout(() => launcherRef.current?.focus(), 0);
  }

  function toggleSize() {
    setPosition(position === "full" ? "half" : "full");
  }

  function expandForInput() {
    if (position === "half" && !isDesktop) setPosition("full");
  }

  return { position, sheetRef, launcherRef, open, move, toggleSize, expandForInput };
}

import type { NativeMapChannel } from "./bridge";
import type { NativeMapLayout, NativeRect } from "./protocol";

const HIT_SELECTOR = "[data-native-map-hit]";

/** The host retains these rectangles for synchronous UIKit hit testing. */
export function startOverlayLayoutBridge(channel: NativeMapChannel, mapRegion: HTMLElement): () => void {
  let frame: number | null = null;
  let stopped = false;
  let previous = "";
  const transitions = new Map<EventTarget, Set<string>>();
  const pointers = new Set<number>();
  const observed = new Set<Element>();
  const observer = new ResizeObserver(schedule);

  function visible(element: HTMLElement): boolean {
    const style = getComputedStyle(element);
    return (
      style.visibility !== "hidden" &&
      style.display !== "none" &&
      !element.hidden &&
      element.getBoundingClientRect().height > 0
    );
  }
  function measure() {
    frame = null;
    if (stopped) return;
    const elements = Array.from(document.querySelectorAll<HTMLElement>(HIT_SELECTOR));
    for (const element of [mapRegion, ...elements]) {
      if (observed.has(element)) continue;
      observed.add(element);
      observer.observe(element);
    }
    for (const element of observed) {
      if (element.isConnected) continue;
      observer.unobserve(element);
      observed.delete(element);
    }
    for (const target of transitions.keys()) {
      if (!(target instanceof Node) || !target.isConnected) transitions.delete(target);
    }
    const viewport = window.visualViewport;
    const layout: NativeMapLayout = {
      viewport: {
        width: viewport?.width ?? window.innerWidth,
        height: viewport?.height ?? window.innerHeight,
        offsetTop: viewport?.offsetTop ?? 0,
        offsetLeft: viewport?.offsetLeft ?? 0,
        scale: viewport?.scale ?? 1,
      },
      mapRect: rectFor(mapRegion.getBoundingClientRect()),
      webRects: elements.filter(visible).map((element) => rectFor(element.getBoundingClientRect())),
      captureAll:
        document.querySelector("dialog[open]") !== null ||
        document.getElementById("root")?.inert === true ||
        mapRegion.getAttribute("aria-hidden") === "true" ||
        mapRegion.getAttribute("aria-busy") === "true",
      transitioning: pointers.size > 0 || transitions.size > 0,
    };
    const signature = JSON.stringify(layout);
    if (signature !== previous) {
      previous = signature;
      channel.send("layout", layout);
    }
    if (layout.transitioning) schedule();
  }
  function schedule() {
    if (!stopped && frame === null) frame = window.requestAnimationFrame(measure);
  }
  function relevant(target: EventTarget | null): target is HTMLElement {
    return target instanceof HTMLElement && (target === mapRegion || target.matches(HIT_SELECTOR));
  }
  function transitionStart(event: TransitionEvent) {
    if (!relevant(event.target)) return;
    const properties = transitions.get(event.target) ?? new Set<string>();
    properties.add(event.propertyName);
    transitions.set(event.target, properties);
    schedule();
  }
  function transitionEnd(event: TransitionEvent) {
    if (!relevant(event.target)) return;
    const properties = transitions.get(event.target);
    properties?.delete(event.propertyName);
    if (properties?.size === 0) transitions.delete(event.target);
    schedule();
  }
  function pointerStart(event: PointerEvent) {
    if (!(event.target instanceof Element) || !event.target.closest("[data-native-map-drag]")) return;
    pointers.add(event.pointerId);
    schedule();
  }
  function pointerEnd(event: PointerEvent) {
    pointers.delete(event.pointerId);
    schedule();
  }
  function clearPointers() {
    pointers.clear();
    schedule();
  }
  const mutations = new MutationObserver(schedule);
  mutations.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: [
      "class",
      "aria-hidden",
      "aria-busy",
      "aria-modal",
      "inert",
      "data-position",
      "data-snap",
      "data-dragging",
      "hidden",
      "open",
    ],
  });
  document.addEventListener("transitionrun", transitionStart);
  document.addEventListener("transitionend", transitionEnd);
  document.addEventListener("transitioncancel", transitionEnd);
  document.addEventListener("pointerdown", pointerStart, true);
  document.addEventListener("pointerup", pointerEnd, true);
  document.addEventListener("pointercancel", pointerEnd, true);
  document.addEventListener("lostpointercapture", pointerEnd, true);
  window.addEventListener("blur", clearPointers);
  window.addEventListener("resize", schedule);
  window.visualViewport?.addEventListener("resize", schedule);
  window.visualViewport?.addEventListener("scroll", schedule);
  measure();
  return () => {
    stopped = true;
    if (frame !== null) window.cancelAnimationFrame(frame);
    observer.disconnect();
    mutations.disconnect();
    document.removeEventListener("transitionrun", transitionStart);
    document.removeEventListener("transitionend", transitionEnd);
    document.removeEventListener("transitioncancel", transitionEnd);
    document.removeEventListener("pointerdown", pointerStart, true);
    document.removeEventListener("pointerup", pointerEnd, true);
    document.removeEventListener("pointercancel", pointerEnd, true);
    document.removeEventListener("lostpointercapture", pointerEnd, true);
    window.removeEventListener("blur", clearPointers);
    window.removeEventListener("resize", schedule);
    window.visualViewport?.removeEventListener("resize", schedule);
    window.visualViewport?.removeEventListener("scroll", schedule);
  };
}

export function rectFor(bounds: Pick<DOMRect, "left" | "top" | "width" | "height">): NativeRect {
  // One-tenth CSS pixel avoids bridge traffic caused only by subpixel measurement noise.
  const rounded = (value: number) => Math.round(value * 10) / 10;
  return {
    x: rounded(bounds.left),
    y: rounded(bounds.top),
    width: rounded(bounds.width),
    height: rounded(bounds.height),
  };
}

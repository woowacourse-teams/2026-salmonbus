import { STARTUP_MARKS, type StartupMarkName } from "../startup/startupMarks";
import type { NativeMapCommands, NativeMapEvent } from "./protocol";

export interface NativeMapStageTracker {
  sent: <T extends keyof NativeMapCommands>(type: T, payload: NativeMapCommands[T], revision: number) => void;
  received: (event: NativeMapEvent) => void;
}

interface SentLayout {
  revision: number;
  open: boolean;
}

function stopCountOf(type: keyof NativeMapCommands, payload: unknown): number | null {
  if (type === "mountHome") return (payload as NativeMapCommands["mountHome"]).content.stations.stops.length;
  if (type === "updateHome") return (payload as NativeMapCommands["updateHome"]).stations?.stops.length ?? null;
  return null;
}

export function createNativeMapStageTracker(mark: (name: StartupMarkName) => boolean): NativeMapStageTracker {
  let ownsStartupMarks = false;
  let ready = false;
  let stopsSent = false;
  let appliedOpen = false;
  let interactive = false;
  let layouts: SentLayout[] = [];
  const settle = () => {
    if (!ownsStartupMarks || interactive || !ready || !stopsSent || !appliedOpen) return;
    interactive = true;
    mark(STARTUP_MARKS.mapStopsInteractive);
  };
  return {
    sent(type, payload, revision) {
      if (type === "mountHome") {
        if (!ownsStartupMarks) ownsStartupMarks = mark(STARTUP_MARKS.mapNativeMount);
        if (!ownsStartupMarks) return;
        ready = false;
        stopsSent = false;
        appliedOpen = false;
        layouts = [];
      }
      if (!ownsStartupMarks) return;
      if (type === "layout") {
        const layout = payload as NativeMapCommands["layout"];
        layouts.push({ revision, open: !layout.captureAll && !layout.transitioning });
      }
      const stopCount = stopCountOf(type, payload);
      if (stopCount !== null) stopsSent = stopCount > 0;
      settle();
    },
    received(event) {
      if (!ownsStartupMarks) return;
      if (event.type === "ready") {
        mark(STARTUP_MARKS.mapNativeReady);
        ready = true;
      }
      if (event.type === "error") {
        mark(STARTUP_MARKS.mapNativeError);
        ready = false;
      }
      if (event.type === "ack") {
        const applied = layouts.filter((layout) => layout.revision <= event.payload.appliedRevision);
        const latest = applied.at(-1);
        if (latest !== undefined) {
          appliedOpen = latest.open;
          layouts = layouts.filter((layout) => layout.revision >= latest.revision);
        }
      }
      settle();
    },
  };
}

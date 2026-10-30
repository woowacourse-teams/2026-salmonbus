import type { EventMap } from "@/shared/analytics/events";
import type { WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { latestIdOf, type WhatsNewCloseMethod } from "@/shared/whats-new/whatsNewPolicy";

export function whatsNewViewedPropertiesOf(
  notes: readonly WhatsNewNote[],
  newRouteCount: number,
): EventMap["whats_new_viewed"] {
  return {
    latest_note_id: latestIdOf(notes),
    feature_count: notes.reduce((count, note) => count + note.features.length, 0),
    new_route_count: newRouteCount,
  };
}

export function whatsNewClosedPropertiesOf(
  notes: readonly WhatsNewNote[],
  closeMethod: WhatsNewCloseMethod,
  openDurationMs: number,
): EventMap["whats_new_closed"] {
  return {
    latest_note_id: latestIdOf(notes),
    close_method: closeMethod,
    open_duration_ms: Math.round(openDurationMs),
  };
}

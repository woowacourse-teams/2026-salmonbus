import { useSyncExternalStore } from "react";
import { homeMapFeed } from "./homeMapFeed";

export function useHomeMapData(needed: boolean) {
  return useSyncExternalStore(needed ? homeMapFeed.subscribeNeeded : homeMapFeed.subscribe, homeMapFeed.getSnapshot)
    .data;
}

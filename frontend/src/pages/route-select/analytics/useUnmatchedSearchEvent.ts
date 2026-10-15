import { useEffect, useRef } from "react";
import { track } from "@/shared/analytics/track";

// 시작값. 1~2자리 query가 많으면 늘리고, 결과 없음을 보고 1초 안에 떠난 세션이 많으면 줄인다.
const UNMATCHED_DEBOUNCE_MS = 1000;

export function useUnmatchedSearchEvent(query: string | null) {
  const sentQueries = useRef(new Set<string>());

  useEffect(() => {
    if (query === null || sentQueries.current.has(query)) return;
    const timer = window.setTimeout(() => {
      sentQueries.current.add(query);
      track("route_search_unmatched", { query });
    }, UNMATCHED_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);
}

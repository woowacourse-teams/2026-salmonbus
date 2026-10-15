import type { RouteSummary } from "@/shared/api/routeForecast.types";
import type { SearchView } from "./displayPolicy";

export const SEARCH_QUESTION = "몇 번 버스를 기다리세요?";
export const SEARCH_PLACEHOLDER = "번호를 입력하면 탑승 확률을 알려드려요";
export const SEARCH_INPUT_LABEL = "노선 번호";
export const CLEAR_INPUT_LABEL = "검색어 지우기";
export const LOADING_NOTICE = "노선을 불러오는 중이에요";
export const LOAD_FAILED_NOTICE = "노선을 불러오지 못했어요";
export const RETRY_LABEL = "다시 시도하기";
export const UNMATCHED_NOTICE = "일치하는 노선이 없어요";
export const SEARCH_RESULTS_LABEL = "검색 결과";

export function matchedCountLabel(count: number): string {
  return `일치하는 노선 ${count}개`;
}

export function searchStatusLabel(view: SearchView): string {
  switch (view.kind) {
    case "idle":
      return "";
    case "loading":
      return LOADING_NOTICE;
    case "error":
      return LOAD_FAILED_NOTICE;
    case "unmatched":
      return UNMATCHED_NOTICE;
    case "matched":
      return matchedCountLabel(view.routes.length);
  }
}

export function routeButtonLabel(route: RouteSummary): string {
  return `${route.displayName}번 노선, ${route.startStopName}부터 ${route.endStopName} 구간`;
}

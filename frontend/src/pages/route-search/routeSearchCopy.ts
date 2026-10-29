import type { ListedRoute, SearchView } from "./displayPolicy";

export const BACK_LABEL = "노선 선택으로 돌아가기";
export const SEARCH_PLACEHOLDER = "노선 번호 검색하기";
export const SEARCH_INPUT_LABEL = "노선 번호";
export const CLEAR_INPUT_LABEL = "검색어 지우기";
export const LOADING_NOTICE = "노선을 불러오는 중이에요";
export const LOAD_FAILED_NOTICE = "노선을 불러오지 못했어요";
export const RETRY_LABEL = "다시 시도하기";
export const RELOAD_LABEL = "다시 불러오기";
export const UNMATCHED_NOTICE = "일치하는 노선이 없어요";
export const IN_SERVICE_TITLE = "서비스 중 노선";
export const NEW_BADGE = "NEW";
const NEW_ROUTE_LABEL = "새 노선";
export const EMPTY_TITLE = "지금 볼 수 있는 노선이 없어요";
export const EMPTY_DESCRIPTION = "잠시 뒤에 다시 불러와 주세요.";

export function matchedCountLabel(count: number): string {
  return `일치하는 노선 ${count}개`;
}

export function searchStatusLabel(view: SearchView): string {
  switch (view.kind) {
    case "empty":
      return `${EMPTY_TITLE}. ${EMPTY_DESCRIPTION}`;
    case "loading":
      return LOADING_NOTICE;
    case "error":
      return LOAD_FAILED_NOTICE;
    case "all":
      return `${IN_SERVICE_TITLE} ${view.routes.length}개`;
    case "unmatched":
      return UNMATCHED_NOTICE;
    case "matched":
      return matchedCountLabel(view.routes.length);
  }
}

export function routeButtonLabel(route: ListedRoute): string {
  const newRouteLabel = route.isNew ? `${NEW_ROUTE_LABEL}, ` : "";
  return `${route.displayName}번 노선, ${newRouteLabel}${route.startStopName}부터 ${route.endStopName} 구간`;
}

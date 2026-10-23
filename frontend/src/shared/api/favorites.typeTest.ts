import type { HomeFavoritesSelection } from "@/shared/hooks/useFavorites";
import type { FavoriteEta, FavoriteStatus, HomeFavorites } from "./favorites.types";

const available: FavoriteEta = {
  status: "AVAILABLE",
  expectedArrivalAt: "2026-10-23T12:00:00+09:00",
  remainingSeconds: 0,
};
const unavailable: FavoriteEta = { status: "UNAVAILABLE", reason: "SOURCE_NOT_PROVIDED" };

// @ts-expect-error 정보 없음 상태에는 남은 시간을 넣을 수 없다.
const unavailableWithTime: FavoriteEta = { status: "UNAVAILABLE", reason: "SOURCE_NOT_PROVIDED", remainingSeconds: 0 };
// @ts-expect-error 정보 있음 상태에는 reason을 넣을 수 없다.
const availableWithReason: FavoriteEta = {
  status: "AVAILABLE",
  expectedArrivalAt: "2026-10-23T12:00:00+09:00",
  remainingSeconds: 0,
  reason: "UNKNOWN",
};
// @ts-expect-error 정보 없음의 사유는 생략할 수 없다.
const unavailableWithoutReason: FavoriteEta = { status: "UNAVAILABLE" };
// @ts-expect-error 새 서버 상태는 런타임 파서와 계약 타입을 함께 고쳐야 한다.
const unknownStatus: FavoriteStatus = "NEW_STATUS";
// @ts-expect-error 홈 응답에는 그룹 목록이 필요하다.
const missingGroups: HomeFavorites = {};

void [
  available,
  unavailable,
  unavailableWithTime,
  availableWithReason,
  unavailableWithoutReason,
  unknownStatus,
  missingGroups,
];

const allGroups: HomeFavoritesSelection = { kind: "all" };
const oneGroup: HomeFavoritesSelection = { kind: "group", groupId: "group-id" };
// @ts-expect-error 전체 조회는 그룹 ID 문자열로 표현하지 않는다.
const legacyAll: HomeFavoritesSelection = "all";
// @ts-expect-error 특정 그룹 조회에는 그룹 ID가 필요하다.
const missingGroupId: HomeFavoritesSelection = { kind: "group" };
void [allGroups, oneGroup, legacyAll, missingGroupId];

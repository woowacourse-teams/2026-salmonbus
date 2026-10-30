import type { FavoriteStatus } from "@/shared/api/favorites.types";

const STATUS_LABEL: Record<Exclude<FavoriteStatus, "READY">, string> = {
  NO_VEHICLE: "도착 예정 차량이 없어요",
  PREPARING: "도착 예보를 준비하고 있어요",
  UNAVAILABLE: "현재 노선에서 이 정류장을 이용할 수 없어요",
  AMBIGUOUS: "승차할 정류장을 다시 확인해 주세요",
  ERROR: "정류장 정보를 불러오지 못했어요",
};

export function favoriteStatusLabelOf(status: Exclude<FavoriteStatus, "READY">) {
  return STATUS_LABEL[status];
}

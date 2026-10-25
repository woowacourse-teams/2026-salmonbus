export function stopsAwayLabelOf(remainingStops: number): string {
  return remainingStops === 0 ? "정류장 도착" : `${remainingStops}정류장 전`;
}

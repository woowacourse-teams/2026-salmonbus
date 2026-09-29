import { isPositiveInteger, isRecord } from "./valueGuards";

export interface ChatAvailability {
  routeId: string;
  displayName: string;
  historySize: number;
  maxBodyCodePoints: number;
}

export function availabilityFrom(value: unknown): ChatAvailability | null {
  if (!isRecord(value)) return null;
  if (
    typeof value.routeId !== "string" ||
    typeof value.displayName !== "string" ||
    !isPositiveInteger(value.historySize) ||
    !isPositiveInteger(value.maxBodyCodePoints)
  ) {
    return null;
  }

  return {
    routeId: value.routeId,
    displayName: value.displayName,
    historySize: value.historySize,
    maxBodyCodePoints: value.maxBodyCodePoints,
  };
}

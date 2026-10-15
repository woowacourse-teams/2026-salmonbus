// 이유 없는 abort()는 정상 취소로 추측하지 않는다.
export const REQUEST_SUPERSEDED = Symbol("request_superseded");
export const REQUEST_DISPOSED = Symbol("request_disposed");

export function cancellationReasonFrom(reason: unknown): "superseded" | "disposed" | "unknown" {
  if (reason === REQUEST_SUPERSEDED) return "superseded";
  if (reason === REQUEST_DISPOSED) return "disposed";
  return "unknown";
}

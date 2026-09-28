import type { ApiFailure } from "@/shared/api/client";
import type { FailureProperties } from "./events";

export function failurePropertiesOf(failure: ApiFailure): FailureProperties | null {
  switch (failure.kind) {
    case "aborted":
      return null;
    case "contract":
      return {
        error_kind: failure.kind,
        http_status: failure.status,
        ...(failure.error.code === "MODEL_OUT_OF_SCOPE" && { error_code: failure.error.code }),
      };
    case "rateLimited":
    case "malformed":
      return { error_kind: failure.kind, http_status: failure.status };
    case "timeout":
    case "network":
      return { error_kind: failure.kind };
  }
}

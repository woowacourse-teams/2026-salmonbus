import type { ApiFailureDetails } from "./client";

const withMethod: ApiFailureDetails = { method: "PUT", context: null, abortReason: null, rawResponse: null };
// @ts-expect-error 실패 정보에는 요청 메서드를 빼놓을 수 없다.
const withoutMethod: ApiFailureDetails = { context: null, abortReason: null, rawResponse: null };
// @ts-expect-error 실패 정보의 메서드는 undefined일 수 없다.
const undefinedMethod: ApiFailureDetails = { method: undefined, context: null, abortReason: null, rawResponse: null };

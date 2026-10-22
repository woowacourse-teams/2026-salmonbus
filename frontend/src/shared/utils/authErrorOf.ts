import type { ApiFailure } from "@/shared/api/client";

export function authErrorOf(failure: ApiFailure): string {
  if (failure.kind === "contract") {
    switch (failure.error.code) {
      case "NICKNAME_TAKEN":
        return "이미 사용 중인 닉네임이에요.";
      case "NICKNAME_LENGTH":
        return "닉네임은 2자 이상 10자 이하로 입력해 주세요.";
      case "NICKNAME_CHARACTERS":
        return "닉네임은 한글, 영문, 숫자만 사용할 수 있어요.";
      case "NICKNAME_RESERVED":
        return "사용할 수 없는 닉네임이에요. 다른 닉네임을 입력해 주세요.";
      case "CSRF_REJECTED":
        return "요청을 확인하지 못했어요. 새로고침한 뒤 다시 시도해 주세요.";
      case "UNAUTHENTICATED":
        return "로그인이 만료됐어요. 다시 로그인해 주세요.";
    }
  }
  if ("status" in failure && failure.status === 404)
    return "로그인 기능을 준비 중이에요. 노선 검색은 계속 이용할 수 있어요.";
  return "요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.";
}

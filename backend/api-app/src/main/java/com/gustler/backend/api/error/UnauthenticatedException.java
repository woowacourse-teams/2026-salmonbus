package com.gustler.backend.api.error;

/**
 * 세션은 있지만 그 회원을 더 이상 찾을 수 없어 로그인이 필요한 상태임을 알린다.
 *
 * <p>세션이 아예 없는 요청은 보안 필터가 먼저 거절한다. 이 예외는 탈퇴한 회원의 세션이 남아 있는 경우처럼
 * 컨트롤러에 도달한 뒤에 알게 되는 경우에 쓴다.
 */
public class UnauthenticatedException extends ApiException {
    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED);
    }
}

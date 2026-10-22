package com.gustler.backend.api.error;

/**
 * 로그인이 필요한 상태임을 알린다.
 *
 * <p>회원 경로에 액세스 토큰이 없거나 검증에 실패한 요청은 보안 필터가 먼저 거절한다. 이 예외는 컨트롤러에 도달한 뒤에
 * 알게 되는 경우에 쓴다. 토큰은 유효하지만 회원이 이미 탈퇴한 경우와 리프레시 토큰으로 갱신하지 못한 경우다.
 */
public class UnauthenticatedException extends ApiException {
    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED);
    }
}

package com.gustler.backend.api.account;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 애플 회원은 다시 인증해야 탈퇴할 수 있음을 알린다.
 *
 * <p>애플 연결을 끊으려면 애플이 새로 발급한 토큰이 필요하고, 우리는 애플 토큰을 저장하지 않는다. 클라이언트는
 * {@code POST /api/me/withdrawal/apple} 로 받은 시작 주소로 이동해 탈퇴를 시작해야 한다.
 */
public class ReauthRequiredException extends ApiException {
    public ReauthRequiredException() {
        super(ErrorCode.REAUTH_REQUIRED);
    }
}

package com.gustler.backend.api.error;

/**
 * 상태를 바꾸는 요청의 CSRF 토큰을 확인하지 못했음을 알린다.
 *
 * <p>보안 필터에서 응답하므로 {@code ApiExceptionHandler} 를 거치지 않는다.
 */
public class CsrfRejectedException extends ApiException {
    public CsrfRejectedException() {
        super(ErrorCode.CSRF_REJECTED);
    }
}

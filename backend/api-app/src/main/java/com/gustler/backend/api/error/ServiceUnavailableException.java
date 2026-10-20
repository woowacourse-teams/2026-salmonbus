package com.gustler.backend.api.error;

/**
 * 읽은 자료의 상태나 일관성 때문에 현재 요청 결과를 제공할 수 없음을 알린다.
 */
public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE);
    }
}

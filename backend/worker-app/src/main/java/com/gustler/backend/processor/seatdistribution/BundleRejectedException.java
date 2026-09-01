package com.gustler.backend.processor.seatdistribution;

/**
 * 계수 묶음을 안 올린 이유.
 *
 * <p>검사 하나라도 어긋나면 던진다. 어긋난 묶음으로 예보를 내는 것보다 예보를 안 내는 편이
 * 낫다. 값이 정상처럼 보이면서 뜻만 없는 상태는 화면에서 구분되지 않는다.
 */
public class BundleRejectedException extends RuntimeException {

    public BundleRejectedException(
        String message
    ) {
        super(message);
    }

    public BundleRejectedException(
        String message,
        Throwable cause
    ) {
        super(message, cause);
    }
}

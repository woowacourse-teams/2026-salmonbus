package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.api.model.ModelLoadException;

/**
 * 계수 묶음의 적재가 거절된 이유.
 *
 * <p>검사 하나라도 어긋나면 던진다. 어긋난 묶음으로 예보를 생성하는 것보다 예보를 생성하지 않는 편이
 * 낫다. 값은 정상처럼 보이지만 의미가 다른 예보를 화면에서 구분할 수 없기 때문이다.
 */
public class BundleRejectedException extends ModelLoadException {
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

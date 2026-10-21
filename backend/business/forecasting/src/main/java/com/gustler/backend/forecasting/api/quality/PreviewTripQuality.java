package com.gustler.backend.forecasting.api.quality;

import java.time.Instant;

/**
 * 변경 없이 지정 범위의 조사 근거를 미리 보는 공개 업무 경계다.
 */
public interface PreviewTripQuality {
    /**
     * 지정 시각까지의 품질 정리 영향을 읽는다. 미리보기 호출로 관측이나 조사 상태를 변경하지 않는다.
     */
    TripQualityPreview preview(long routeVersionId, Instant until);
}

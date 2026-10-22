package com.gustler.backend.forecasting.api.quality;

/**
 * 대기 중인 편도 품질 조사를 제한된 회차로 진행하는 공개 업무 경계다.
 */
public interface InvestigateTripQuality {
    /**
     * 다음 미완료 품질 조사를 제한된 범위에서 진행한다. 실제 저장과 커밋은 조사 유스케이스 경계를 따른다.
     */
    void investigate();
}

package com.gustler.backend.forecasting.api.evaluation;

/**
 * 완료 정산의 이관 작업을 정책에 따라 제한된 한 회차만 진행하는 공개 업무 경계다.
 */
public interface AdvanceEvaluationArchive {
    /**
     * 재개할 배치 또는 새 후보를 골라 아카이브 한 단계를 진행한다. 외부 파일 검증과 DB 확정을 분리한다.
     */
    void advance();
}

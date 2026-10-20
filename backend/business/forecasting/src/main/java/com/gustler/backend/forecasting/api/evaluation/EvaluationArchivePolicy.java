package com.gustler.backend.forecasting.api.evaluation;

/**
 * 정산 이관의 묶음 크기·재시도·삭제 활성 여부를 전달한다.
 * 파일 검증과 원본 삭제를 구분한다.
 */
public record EvaluationArchivePolicy(boolean deleteEnabled, int pageSize) {
    public EvaluationArchivePolicy {
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("이관 페이지는 1~1000개여야 한다");
        }
    }
}

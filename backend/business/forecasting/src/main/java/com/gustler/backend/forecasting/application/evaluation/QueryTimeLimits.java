package com.gustler.backend.forecasting.application.evaluation;

import java.time.Duration;

/**
 * 현재 업무 조회에 DB 실행 시간 예산을 적용하는 경계다.
 */
public interface QueryTimeLimits {
    /**
     * 현재 DB 작업의 문장 실행과 잠금 대기 시간 제한을 설정한다. Duration 단위 그대로 전달하며 외부 HTTP 시간 제한과 구별한다.
     */
    void apply(Duration statementTimeout, Duration lockTimeout);
}

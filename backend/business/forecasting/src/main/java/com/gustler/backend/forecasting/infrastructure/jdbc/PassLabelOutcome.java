package com.gustler.backend.forecasting.infrastructure.jdbc;

/**
 * 통과 하나의 정답 계산 결과다. 정답이 정해지면 상태가 {@code SETTLED}이고 정답 값·계수 해시·정원을 담는다.
 * 정해지지 않으면 {@code UNSUPPORTED}와 사유만 담는다.
 */
record PassLabelOutcome(
    String state,
    Double label,
    String reason,
    String parameterHash,
    Integer capacity
) {
    static PassLabelOutcome unsupported(String reason) {
        return new PassLabelOutcome("UNSUPPORTED", null, reason, null, null);
    }
}

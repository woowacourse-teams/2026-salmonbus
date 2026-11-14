package com.gustler.backend.forecasting.application.pipeline;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 통과 하나의 정답이다. 정답은 통과·직전 통과·정원·모델 계수로 정해지고 예측값과는 무관하다.
 *
 * <p>채점됨({@link State#SETTLED})이면 정답 값이 있고 사유가 없다. 지원 안 함({@link State#UNSUPPORTED})이면 정답 값이 없고
 * 사유가 있다. 이 규칙은 {@code pass_label}의 CHECK와 같다.
 *
 * @param sourceDate 통과를 닫은 배치의 나눔 날짜
 * @param routeId 노선
 * @param tripStartObservationId 운행 시작 관측
 * @param stopOrder 정류장 순번
 * @param capacity 정답을 계산한 정원
 * @param labelVersion 정답 판
 * @param state 정답 상태
 * @param reason 지원 안 함 사유
 * @param labelValue 정답 값
 * @param previousLastObservationId 직전 통과의 마지막 관측
 * @param previousClosureObservationId 직전 통과를 닫은 관측
 * @param parameterHash 정답 계산에 쓴 계수의 SHA-256 16진수 64자
 * @param computedAt 계산 시각
 */
public record PassLabel(
    LocalDate sourceDate,
    long routeId,
    long tripStartObservationId,
    int stopOrder,
    int capacity,
    String labelVersion,
    State state,
    String reason,
    Double labelValue,
    Long previousLastObservationId,
    Long previousClosureObservationId,
    String parameterHash,
    Instant computedAt
) {
    public PassLabel {
        Objects.requireNonNull(sourceDate, "나눔 날짜가 필요하다");
        Objects.requireNonNull(state, "정답 상태가 필요하다");
        Objects.requireNonNull(computedAt, "계산 시각이 필요하다");
        if (labelVersion == null || labelVersion.isBlank()) {
            throw new IllegalArgumentException("정답 판이 필요하다");
        }
        final boolean settled = state == State.SETTLED;
        if (settled != (labelValue != null) || settled != (reason == null)) {
            throw new IllegalArgumentException("채점됨은 값만, 지원 안 함은 사유만 가진다: " + state);
        }
        if (parameterHash != null && !parameterHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("계수 해시는 소문자 16진수 64자여야 한다");
        }
    }

    /** 정답 상태. 이름은 {@code pass_label.state}에 그대로 저장된다. */
    public enum State {
        SETTLED,
        UNSUPPORTED
    }
}

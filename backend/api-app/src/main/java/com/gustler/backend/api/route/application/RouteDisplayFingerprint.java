package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.application.RouteDisplayRepository.StoredInputs;

/**
 * 표시 선택 결과를 정하는 입력. 같은 원본 형상에서 이 값이 같으면 {@link RouteDisplaySelector#enrich}의 결과도 같다.
 *
 * <p>{@code enabled}가 거짓이면 표시 선택을 하지 않아 나머지는 null이다. 원본 입력이 유효하지 않으면 {@code inputDigest}와
 * {@code stored}가 null이다. {@code evidenceRevision}은 조회 시각으로 다시 평가한 보조 근거의 판본이다.
 */
public record RouteDisplayFingerprint(boolean enabled, String inputDigest, String evidenceRevision, StoredInputs stored) {
    private static final RouteDisplayFingerprint DISABLED = new RouteDisplayFingerprint(false, null, null, null);

    public static RouteDisplayFingerprint disabled() {
        return DISABLED;
    }

    public static RouteDisplayFingerprint invalidInput(String evidenceRevision) {
        return new RouteDisplayFingerprint(true, null, evidenceRevision, null);
    }
}

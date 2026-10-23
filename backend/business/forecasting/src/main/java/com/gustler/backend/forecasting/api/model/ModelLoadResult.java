package com.gustler.backend.forecasting.api.model;

/**
 * 모델 적재의 성공과 사용할 수 없는 상태를 구분한다.
 * 실패한 입력을 활성 모델로 노출하지 않는다.
 */
public sealed interface ModelLoadResult {
    record NotConfigured() implements ModelLoadResult {
    }

    record Rejected(String reason) implements ModelLoadResult {
    }

    record Activated(long deploymentId) implements ModelLoadResult {
    }

    record Reloaded(String releaseId) implements ModelLoadResult {
    }

    record IdentityMismatch(String activeReleaseId, String fileReleaseId) implements ModelLoadResult {
    }

    record Promoted(String activeReleaseId, String fileReleaseId, long deploymentId) implements ModelLoadResult {
    }
}

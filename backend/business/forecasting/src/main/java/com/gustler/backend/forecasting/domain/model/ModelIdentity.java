package com.gustler.backend.forecasting.domain.model;

import java.time.Instant;
import java.util.Objects;

/** 모델 파일과 저장된 배포를 대조하는 전체 식별 정보다. */
public record ModelIdentity(
    String releaseId,
    String modelKey,
    String modelVersion,
    String bundleDigest,
    String predictionTargetVersion,
    String calculationVersion,
    String supportedScopeDigest,
    Instant dataUntil
) {
    public ModelIdentity {
        requireText(releaseId, "releaseId", 80);
        requireText(modelKey, "modelKey", 40);
        requireText(modelVersion, "modelVersion", 60);
        requireText(predictionTargetVersion, "predictionTargetVersion", 40);
        requireText(calculationVersion, "calculationVersion", 40);
        requireDigest(bundleDigest, "bundleDigest");
        requireDigest(supportedScopeDigest, "supportedScopeDigest");
        Objects.requireNonNull(dataUntil, "dataUntil");
        // PostgreSQL/JDBC가 timestamptz에 저장하는 마이크로초 정밀도로 비교한다.
        // 원본 모델 파일은 그대로 두고 0.5 마이크로초 이상을 반올림하며 초 경계도 처리한다.
        long roundedMicros = (dataUntil.getNano() + 500L) / 1_000L;
        dataUntil = Instant.ofEpochSecond(dataUntil.getEpochSecond(), roundedMicros * 1_000L);
    }

    private static void requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + "는 " + maximumLength + "자를 넘을 수 없습니다");
        }
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + "는 소문자 SHA-256 값이어야 합니다");
        }
    }
}

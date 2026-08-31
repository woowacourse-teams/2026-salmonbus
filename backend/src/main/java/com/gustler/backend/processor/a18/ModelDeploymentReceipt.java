package com.gustler.backend.processor.a18;

import java.time.Instant;
import java.util.UUID;

/**
 * 계수 묶음 하나를 적재했다는 영수증. {@code model_deployment} 한 줄이 된다.
 *
 * <p>{@code V2__model.sql} 은 {@code deployment_key} · {@code release_id} · {@code model_key} ·
 * {@code model_version} 넷이 서로 무엇이 다른지 안 적고 SAL-93 이 정하라고 남겨 뒀다. 이렇게 나눈다.
 *
 * <ul>
 *   <li>{@code model_key} 는 모델 계열이다. 좌석 분포를 내는 A18 계열이 하나다
 *   <li>{@code model_version} 은 그 계열의 판이다. 수식이 바뀌면 오른다
 *   <li>{@code release_id} 는 학습 1회분이다. 같은 판을 다시 학습하면 이것만 바뀐다
 *   <li>{@code deployment_key} 는 <b>적재 1회분</b>이다. 같은 출시를 두 번 올리면 이것만 다르다
 * </ul>
 *
 * <p>{@code calculation_version} 에는 특징 계약 판 이름이 들어간다. 그 열의 뜻이 입력값을 만드는
 * 규칙이고 특징 계약이 정확히 그것이다. 셀 통계도 같은 판 이름으로 세대를 고른다.
 */
public record ModelDeploymentReceipt(
    UUID deploymentKey,
    String releaseId,
    String modelKey,
    String modelVersion,
    String bundleDigest,
    String predictionTargetVersion,
    String calculationVersion,
    String supportedScopeDigest,
    Instant dataUntil
) {

    /** 이 모델이 무엇을 내는지. 만석 확률 하나가 아니라 잔여석 0석부터 70석까지의 분포다. */
    private static final String PREDICTION_TARGET_VERSION = "seat-distribution-0-70";

    private static final String MODEL_KEY = "seat-distribution-a18";

    static ModelDeploymentReceipt of(
        BundleManifest manifest,
        SupportedForecastScope scope,
        UUID deploymentKey
    ) {
        return new ModelDeploymentReceipt(
            deploymentKey,
            manifest.releaseId(),
            MODEL_KEY,
            manifest.modelVersion(),
            manifest.identityDigest(),
            PREDICTION_TARGET_VERSION,
            manifest.featureContractVersion(),
            scope.digest(),
            Instant.parse(manifest.dataThrough()));
    }
}

package com.gustler.backend.processor.a18;

import java.time.Instant;

/**
 * 지금 도는 배포와 그 배포의 예측기를 한 덩어리로 든 것.
 *
 * <p><b>DB 행과 예측기를 따로 읽지 않는다.</b> 따로 읽으면 승격이 일어나는 순간에 ACTIVE 행은
 * B 인데 계산은 A 계수로 하고 DB 에는 B 예보라고 적는 상태가 된다. 값은 정상으로 보이고
 * 나중에 어느 계수로 낸 예보인지 알 방법이 없다.
 *
 * <p>불변식 넷이다.
 *
 * <ul>
 *   <li>이 안의 배포 신원과 예측기 신원은 같은 묶음에서 나왔다
 *   <li>batch 하나는 처음부터 끝까지 같은 것을 쓴다
 *   <li>승격이 일어나도 이미 받아 든 것은 안 바뀐다. 진행 중 batch 가 쓰던 계수가 안 사라진다
 *   <li>예보 행에 배포 식별자뿐 아니라 출시 식별자와 요약값을 대조할 수 있다
 * </ul>
 */
public record RuntimeSnapshot(
    long deploymentId,
    String releaseId,
    String bundleDigest,
    String featureContractVersion,
    SupportedForecastScope scope,
    A18Predictor predictor,
    Instant dataUntil
) {

    public RuntimeSnapshot {
        if (releaseId == null || releaseId.isBlank()) {
            throw new IllegalArgumentException("배포에는 출시 식별자가 있어야 한다");
        }
        if (bundleDigest == null || bundleDigest.length() != 64) {
            throw new IllegalArgumentException("계수 묶음 요약값은 64자리다: " + bundleDigest);
        }
        if (featureContractVersion == null || featureContractVersion.isBlank()) {
            throw new IllegalArgumentException("배포에는 특징 계약 판 이름이 있어야 한다");
        }
        if (predictor == null) {
            throw new IllegalArgumentException("배포에는 예측기가 있어야 한다");
        }
    }
}

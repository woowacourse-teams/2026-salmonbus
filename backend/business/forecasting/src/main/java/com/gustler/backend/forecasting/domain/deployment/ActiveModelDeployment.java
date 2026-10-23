package com.gustler.backend.forecasting.domain.deployment;

/**
 * 현재 활성 상태인 계수 배포다.
 *
 * <p>한 batch의 예보 행은 모두 같은 배포를 가리킨다. 승격 도중에 batch의 배포가 달라지면
 * 해당 batch의 차량이 화면에서 모두 사라진다.
 *
 * <p>배포 식별자만으로는 부족하므로 출시 식별자와 계수 묶음 요약값도 함께 보관한다. 둘을 사용해
 * 메모리에 적재된 계수가 이 행이 가리키는 계수인지 대조한다. 대조하지 않으면
 * ACTIVE 행은 B인데 계산은 A 계수로 하고 DB에는 B의 예보로 기록하는 상태가 가능하다.
 */
public record ActiveModelDeployment(
    long id,
    String calculationVersion,
    String releaseId,
    String bundleDigest
) {
    public ActiveModelDeployment {
        if (calculationVersion == null || calculationVersion.isBlank()) {
            throw new IllegalArgumentException("배포에는 계산 규칙 판이 있어야 한다: " + calculationVersion);
        }
        if (releaseId == null || releaseId.isBlank()) {
            throw new IllegalArgumentException("배포에는 출시 식별자가 있어야 한다: " + releaseId);
        }
        if (bundleDigest == null || bundleDigest.length() != 64) {
            throw new IllegalArgumentException("계수 묶음 요약값은 64자리다: " + bundleDigest);
        }
    }
}

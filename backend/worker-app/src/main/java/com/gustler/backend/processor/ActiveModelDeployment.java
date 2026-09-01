package com.gustler.backend.processor;

/**
 * 지금 도는 계수 배포.
 *
 * <p>한 batch 의 예보 행은 전부 같은 배포를 가리킨다. 승격 도중에 batch 가 갈리면
 * 그 batch 의 차량이 화면에서 통째로 사라진다.
 *
 * <p>식별자만으로는 모자라서 출시 식별자와 계수 묶음 요약값을 같이 든다. 그 둘이 있어야
 * 메모리에 올라온 계수가 이 행이 가리키는 그 계수인지 대조할 수 있다. 대조가 없으면
 * ACTIVE 행은 B 인데 계산은 A 계수로 하고 DB 에는 B 예보라고 적는 상태가 가능하다.
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

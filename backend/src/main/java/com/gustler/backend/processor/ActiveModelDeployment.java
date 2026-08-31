package com.gustler.backend.processor;

/**
 * 지금 도는 계수 배포.
 *
 * <p>한 판의 예보 행은 전부 같은 배포를 가리킨다. 승격 도중에 판이 갈리면
 * 그 판의 차량이 화면에서 통째로 사라진다.
 */
public record ActiveModelDeployment(
    long id,
    String calculationVersion
) {

    public ActiveModelDeployment {
        if (calculationVersion == null || calculationVersion.isBlank()) {
            throw new IllegalArgumentException("배포에는 계산 규칙 판이 있어야 한다: " + calculationVersion);
        }
    }
}

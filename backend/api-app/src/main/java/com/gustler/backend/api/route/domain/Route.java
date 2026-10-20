package com.gustler.backend.api.route.domain;

import java.util.Objects;

/**
 * 공개 노선 조회에 필요한 표시값과 식별을 전달한다.
 * 관측·예보 쓰기 상태는 별도 업무가 관리한다.
 */
public record Route(
    String id,
    String displayName,
    String startStopName,
    String endStopName
) {
    public Route {
        Objects.requireNonNull(id, "id는 null일 수 없습니다.");
        Objects.requireNonNull(displayName, "displayName은 null일 수 없습니다.");
        Objects.requireNonNull(startStopName, "startStopName은 null일 수 없습니다.");
        Objects.requireNonNull(endStopName, "endStopName은 null일 수 없습니다.");
    }
}

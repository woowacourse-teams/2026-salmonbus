package com.gustler.backend.api.favorite.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 회원이 저장한 노선과 실제 탑승 정류장의 조합이다.
 * 노선 판본과 방문 순번은 현재 자료에서 다시 계산하므로 영구 식별에 포함하지 않는다.
 */
public record FavoriteTarget(
    String routeId,
    String stopId,
    FavoriteDirection direction
) {
    private static final Pattern PUBLIC_ID_FORMAT = Pattern.compile("[0-9]{9}");

    public FavoriteTarget {
        requirePublicId(routeId, "routeId");
        requirePublicId(stopId, "stopId");
        Objects.requireNonNull(direction, "direction은 null일 수 없습니다.");
    }

    private static void requirePublicId(String value, String field) {
        Objects.requireNonNull(value, field + "는 null일 수 없습니다.");
        if (!PUBLIC_ID_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "는 9자리 숫자여야 합니다.");
        }
    }
}

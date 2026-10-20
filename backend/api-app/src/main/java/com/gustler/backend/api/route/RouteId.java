package com.gustler.backend.api.route;

import java.util.regex.Pattern;

/**
 * 공개 GBIS 노선 식별을 검증하는 값이다.
 * DB 내부 노선 행 식별과 구분한다.
 */
public record RouteId(
    String value
) {
    private static final Pattern FORMAT = Pattern.compile("[0-9]{9}");

    public RouteId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidRouteIdException();
        }
    }
}

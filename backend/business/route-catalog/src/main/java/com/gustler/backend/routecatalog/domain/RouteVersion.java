package com.gustler.backend.routecatalog.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 저장 매핑과 분리한 노선 버전. 종료된 버전의 시간표나 기간은 다시 바꾸지 않는다. */
public record RouteVersion(
    Long id,
    OffsetDateTime validFrom,
    OffsetDateTime validTo,
    RouteVersionContent content
) {

    public RouteVersion {
        Objects.requireNonNull(validFrom);
        Objects.requireNonNull(content);
        if (id != null && id < 1) {
            throw new IllegalArgumentException("노선 버전 ID는 양수여야 한다");
        }
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("노선 버전은 시작한 뒤에 종료해야 한다");
        }
    }

    public static RouteVersion open(RouteVersionContent content, OffsetDateTime at) {
        return new RouteVersion(null, at, null, content);
    }

    public void requireCurrent() {
        if (validTo != null) {
            throw new IllegalStateException("노선 버전 %s는 %s에 이미 종료됐다".formatted(id, validTo));
        }
    }

    public RouteVersion closeAt(OffsetDateTime at) {
        requireCurrent();
        if (!at.isAfter(validFrom)) {
            throw new IllegalArgumentException("새 버전은 현재 버전의 시작 시각 뒤에 열어야 한다");
        }
        return new RouteVersion(id, validFrom, at, content);
    }

    public RouteVersion reviseTimetable(RouteTimetable timetable) {
        requireCurrent();
        return new RouteVersion(id, validFrom, null,
            new RouteVersionContent(content.contentDigest(), timetable));
    }
}

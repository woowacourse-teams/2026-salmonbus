package com.gustler.backend.api.boarding.application;

import com.gustler.backend.api.boarding.BoardingOptionsException;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteId;
import java.util.Objects;
import java.util.Set;

/**
 * 탑승 후보 조회 조건. 범위를 벗어난 값은 400으로 거절한다.
 */
public record BoardingOptionsQuery(
    RouteId routeId,
    String stationId,
    Integer stopSequence,
    Long referenceVersionId,
    Set<AccessMode> modes,
    int maxMinutes,
    int limit,
    Set<StopRelation> relations,
    boolean boardingOnly
) {
    public static final int DEFAULT_MAX_MINUTES = 15;
    public static final int MAX_MINUTES = 30;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    public BoardingOptionsQuery {
        Objects.requireNonNull(routeId, "routeId는 null일 수 없습니다.");
        if (stationId == null || !stationId.matches("[0-9]{1,20}")) {
            throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
        }
        if (stopSequence != null && stopSequence < 1 || referenceVersionId != null && referenceVersionId < 1) {
            throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
        }
        if (maxMinutes < 1 || maxMinutes > MAX_MINUTES || limit < 1 || limit > MAX_LIMIT) {
            throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
        }
        modes = Set.copyOf(modes);
        if (modes.isEmpty()) {
            throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
        }
        relations = Set.copyOf(relations);
        if (relations.isEmpty()) {
            throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
        }
    }
}

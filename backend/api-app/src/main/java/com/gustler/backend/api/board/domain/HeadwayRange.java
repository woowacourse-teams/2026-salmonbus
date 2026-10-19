package com.gustler.backend.api.board.domain;

import java.util.Optional;

public record HeadwayRange(
    int minMinutes,
    int maxMinutes
) {

    private static final int SHORTEST_MINUTES = 1;
    private static final int LONGEST_MINUTES = 1440;

    public HeadwayRange {
        if (!isValid(minMinutes, maxMinutes)) {
            throw new IllegalArgumentException("배차간격은 %d~%d분이고 최소가 최대보다 클 수 없습니다: %d~%d"
                .formatted(SHORTEST_MINUTES, LONGEST_MINUTES, minMinutes, maxMinutes));
        }
    }

    public static Optional<HeadwayRange> of(
        Integer minMinutes,
        Integer maxMinutes
    ) {
        if (minMinutes == null || maxMinutes == null || !isValid(minMinutes, maxMinutes)) {
            return Optional.empty();
        }
        return Optional.of(new HeadwayRange(minMinutes, maxMinutes));
    }

    private static boolean isValid(
        final int minMinutes,
        final int maxMinutes
    ) {
        return minMinutes >= SHORTEST_MINUTES
            && maxMinutes >= minMinutes
            && maxMinutes <= LONGEST_MINUTES;
    }
}

package com.gustler.backend.routecatalog.domain;

import java.util.Optional;

/**
 * 원천의 최소·최대 배차간격 범위다.
 * 단위는 분이며, 읽을 수 없는 원천 값은 제공 가능한 범위와 구분한다.
 */
public record HeadwayRange(
    int minMinutes,
    int maxMinutes
) {
    private static final int SHORTEST_MINUTES = 1;
    private static final int LONGEST_MINUTES = 1440;

    public HeadwayRange {
        if (!isValid(minMinutes, maxMinutes)) {
            throw new IllegalArgumentException("배차간격은 %d~%d분이고 최소가 최대보다 클 수 없다: %d~%d"
                .formatted(SHORTEST_MINUTES, LONGEST_MINUTES, minMinutes, maxMinutes));
        }
    }

    public static Optional<HeadwayRange> of(
        Integer minMinutes,
        Integer maxMinutes
    ) {
        if (isUnavailable(minMinutes, maxMinutes)) {
            return Optional.empty();
        }
        return Optional.of(new HeadwayRange(minMinutes, maxMinutes));
    }

    private static boolean isUnavailable(Integer minMinutes, Integer maxMinutes) {
        if (minMinutes == null || maxMinutes == null) {
            return true;
        }
        return !isValid(minMinutes, maxMinutes);
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

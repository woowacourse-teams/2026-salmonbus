package com.gustler.backend.processor;

public record Horizon(
    int stopsAhead
) {

    private static final int FIRST_STOP_AHEAD = 1;
    private static final int LAST_STOP_AHEAD = 12;

    public Horizon {
        if (stopsAhead < FIRST_STOP_AHEAD || stopsAhead > LAST_STOP_AHEAD) {
            throw new IllegalArgumentException(
                "예보는 %d정류장 앞부터 %d정류장 앞까지만 낸다: %d".formatted(FIRST_STOP_AHEAD, LAST_STOP_AHEAD, stopsAhead)
            );
        }
    }

    public static Horizon of(
        final int stopsAhead
    ) {
        return new Horizon(stopsAhead);
    }
}

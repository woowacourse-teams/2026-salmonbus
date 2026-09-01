package com.gustler.backend.api.board.domain;

/**
 * 보드가 차량을 싣는 거리. 1정류장 앞부터 12정류장 앞까지다.
 *
 * <p>예보를 내는 쪽에도 같은 규칙이 있으나 api 는 processor 를 참조할 수 없어 여기 따로 둔다.
 * {@code PackageBoundaryTest} 가 그 참조를 막는다. 두 자리의 값이 갈리면 좌석을 아는 차량과
 * 모르는 차량이 서로 다른 거리까지 실린다.
 */
public final class ForecastHorizon {

    private static final int NEAREST_STOP_COUNT = 1;
    private static final int FARTHEST_STOP_COUNT = 12;

    private ForecastHorizon() {
    }

    /** 그 거리를 보드에 싣는가. 이미 지난 정류장과 13정류장 앞부터는 싣지 않는다. */
    public static boolean covers(
        final int stopCount
    ) {
        return NEAREST_STOP_COUNT <= stopCount && stopCount <= FARTHEST_STOP_COUNT;
    }
}

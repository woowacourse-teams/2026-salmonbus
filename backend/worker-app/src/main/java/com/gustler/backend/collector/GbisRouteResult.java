package com.gustler.backend.collector;

/**
 * 노선정보를 읽어본 결과.
 *
 * <p>위치정보와 달리 갈래를 잘게 안 나눈다. 노선정보는 판본이 없을 때 한 번 부르는 것이고,
 * 실패하면 어느 갈래든 그 노선을 이번엔 건너뛰는 것으로 같다. 판마다 결말을 적는 표도 없다.
 * 나중에 갈래마다 다르게 굴어야 하면 그때 나눈다.
 */
public sealed interface GbisRouteResult {

    record Success(
        UpstreamRoute route
    ) implements GbisRouteResult {
    }

    record Failed(
        String reason
    ) implements GbisRouteResult {
    }
}

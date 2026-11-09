package com.gustler.backend.stations.domain;

import com.gustler.backend.station.contract.StationTileId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 채울 칸 목록·진행률·정류장 원본을 저장하는 포트다. 쓰기는 짧은 트랜잭션 하나씩이다.
 */
public interface StationTileStore {

    /**
     * 우리 노선 현재 판본 승차 정류장 좌표가 있는 칸.
     */
    Set<StationTileId> salmonTiles();

    /**
     * 없는 칸만 넣는다. 넣은 수를 돌려준다.
     */
    int enqueue(Set<StationTileId> tiles, int priority, Instant now);

    /**
     * 진행 중인 회차, 시작 전 칸, 재채우기 대상 순으로 다음 칸을 고른다. 없으면 빈 값이다.
     */
    Optional<TilePass> next(Instant now, Instant refreshBefore);

    /**
     * 탐색점 하나의 결과를 저장한다. 마지막 점이면 회차를 마치고 이웃 칸을 넣는다.
     */
    void recordProbe(TilePass pass, List<BusStation> stations, Instant seenAt);

    /**
     * 실패를 기록하고 retryAfter까지 다시 고르지 않는다. 이번 회차의 연속 실패 수를 돌려준다.
     * 다른 실행이 이미 회차를 바꿨으면 기록하지 않고 0을 돌려준다.
     */
    int fail(TilePass pass, String reason, Instant retryAfter);

    /**
     * 이번 접수를 거둔다. 대기열에서 빠지고 진행 중인 회차도 버리며, 이전 정류장 자료는 그대로 둔다.
     * 다시 접수되면 처음 탐색점부터 새 회차로 채우되 retryAfter 전에는 고르지 않는다.
     */
    void giveUp(TilePass pass, String reason, Instant retryAfter);

    /**
     * 진행 중인 회차. probesDone은 이미 끝낸 탐색점 수다.
     */
    record TilePass(StationTileId tile, Instant passStartedAt, int probesDone) {
    }
}

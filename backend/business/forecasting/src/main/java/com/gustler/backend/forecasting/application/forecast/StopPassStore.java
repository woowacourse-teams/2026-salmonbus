package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 닫힌 통과({@code stop_pass})를 저장하고 읽는다.
 *
 * <p>쓰기는 호출하는 쪽이 연 트랜잭션 안에서만 하며 이미 있는 키를 건너뛴다. {@code route} 행 잠금을 잡지 않는다.
 */
public interface StopPassStore {

    /**
     * 통과를 여러 줄 한 문장으로 넣는다.
     *
     * @param passes 저장할 통과
     * @return 새로 넣은 줄 수. 이미 있던 키는 세지 않는다
     */
    int save(List<PassClosed> passes);

    /**
     * 노선의 통과를 닫힌 시각이 범위 안에 든 것만 사건 순서로 읽는다. 되감기용이다.
     *
     * <p>순서는 닫힌 시각, 닫은 배치, 닫은 관측, 운행 시작 관측, 정류장 순번이다.
     *
     * @param routeId 노선
     * @param fromInclusive 닫힌 시각 하한(포함)
     * @param toExclusive 닫힌 시각 상한(제외)
     * @return 통과 목록
     */
    List<PassClosed> findClosedBetween(long routeId, Instant fromInclusive, Instant toExclusive);

    /**
     * 노선의 통과 가운데 정답({@code pass_label})이 저장된 것 중 가장 늦게 닫힌 것의 닫힌 시각을 찾는다. 채점이 그 노선을 저장까지
     * 마친 사건 시각이며, 되감기가 예보를 어디서부터 다시 읽을지 정하는 데 쓴다.
     *
     * @param routeId 노선
     * @param fromInclusive 닫힌 시각 하한(포함)
     * @param toExclusive 닫힌 시각 상한(제외)
     * @return 범위 안에 정답이 저장된 통과가 없으면 빈 값
     */
    Optional<Instant> findLastScoredClosedAt(long routeId, Instant fromInclusive, Instant toExclusive);
}

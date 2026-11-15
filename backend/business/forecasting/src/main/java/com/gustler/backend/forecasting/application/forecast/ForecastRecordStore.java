package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 예보 실행({@code forecast_run})과 예보 기록({@code forecast_record})을 저장하고 읽는다.
 *
 * <p>쓰기는 호출하는 쪽이 연 트랜잭션 안에서만 한다. 모든 쓰기는 이미 있는 키를 건너뛰므로 같은 사건을 다시 저장해도 결과가 같다.
 * {@code route} 행 잠금을 잡지 않는다.
 */
public interface ForecastRecordStore {
    /**
     * 실행 한 줄과 버스별 기록을 한 트랜잭션에서 저장하면서, 같은 배치의 수집 사건이 알려 주는 수집 구멍 여부와 하루 관측 상한에
     * 더할 줄 수를 실행 줄에 함께 적는다. 기록은 여러 줄을 한 문장으로 넣는다. 수집 사건은 저장되지 않으므로 채점 되감기는 이 값으로
     * 그날의 수집 구멍과 관측 줄 수를 되살린다({@link #findRecordedBatchesBetween}). 실행 줄이 이미 있으면 그 줄의 값을 바꾸지
     * 않는다. 두 사건의 배치가 다르면 아무것도 저장하지 않고 예외를 던진다.
     *
     * @param published 저장할 사건
     * @param seen 같은 배치의 수집 사건
     * @return 실행 줄을 새로 넣었으면 참, 같은 (날짜, 배치)가 이미 있어 건너뛰었으면 거짓
     */
    boolean save(ForecastPublished published, ObservationBatchSeen seen);

    /**
     * 노선(모든 판본)의 실행을 수신 시각이 범위 안에 든 것만 사건 순서(수신 시각, 배치 번호)로 읽어 사건으로 되살린다.
     * 되감기용이다. 버스별 예측은 배치 안 줄 번호 순이다.
     *
     * @param routeId 노선
     * @param fromInclusive 수신 시각 하한(포함)
     * @param toExclusive 수신 시각 상한(제외)
     * @return 사건 목록
     */
    List<ForecastPublished> findPublishedBetween(long routeId, Instant fromInclusive, Instant toExclusive);

    /**
     * 노선(모든 판본)의 실행 가운데 수신 시각이 범위 안이고 수집 사실이 적힌 것만 사건 순서(수신 시각, 배치 번호)로 읽는다.
     * 되감기용이다. 버스별 기록은 읽지 않는다.
     *
     * @param routeId 노선
     * @param fromInclusive 수신 시각 하한(포함)
     * @param toExclusive 수신 시각 상한(제외)
     * @return 배치별 수집 사실
     */
    List<RecordedBatch> findRecordedBatchesBetween(long routeId, Instant fromInclusive, Instant toExclusive);

    /**
     * 노선의 실행 가운데 미해결 줄({@code forecast_unresolved})이 저장된 예측을 가진 것 중 가장 늦게 수신한 것의 착수 시각을 찾는다.
     * 채점이 그 노선을 저장까지 마친 사건 시각이며, 되감기가 예보를 어디서부터 다시 읽을지 정하는 데 쓴다.
     *
     * @param routeId 노선
     * @param fromInclusive 수신 시각 하한(포함)
     * @param toExclusive 수신 시각 상한(제외)
     * @return 범위 안에 미해결 줄이 저장된 예측이 없으면 빈 값
     */
    Optional<Instant> findLastUnresolvedConfirmedAt(long routeId, Instant fromInclusive, Instant toExclusive);
}

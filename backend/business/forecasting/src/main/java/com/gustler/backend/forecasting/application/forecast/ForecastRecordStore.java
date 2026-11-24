package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 예보 실행({@code forecast_run})과 예보 기록({@code forecast_record})을 저장하고 읽는다.
 *
 * <p>쓰기는 호출자가 연 트랜잭션 안에서만 한다. 모든 쓰기는 이미 있는 키를 건너뛰므로 같은 이벤트를 다시 저장해도 결과가 같다.
 * {@code route} 행 잠금을 사용하지 않는다.
 */
public interface ForecastRecordStore {

    /**
     * 실행 한 행과 버스별 기록을 한 트랜잭션에서 저장하면서, 같은 배치의 수집 이벤트가 알려 주는 관측 누락 여부와 하루 관측 상한에
     * 더할 행 수를 실행 기록에 같이 기록한다. 기록은 여러 행을 한 문장으로 넣는다. 수집 이벤트는 저장되지 않으므로 정산 복구는 이 값으로
     * 그날의 관측 누락과 관측 행 수를 되살린다({@link #findRecordedBatchesBetween}). 실행 기록이 이미 있으면 그 행의 값을 바꾸지
     * 않는다. 두 이벤트의 배치가 다르면 아무것도 저장하지 않고 예외를 던진다.
     *
     * @param published 저장할 이벤트
     * @param seen 같은 배치의 수집 이벤트
     * @return 실행 기록을 새로 저장하었으면 참, 같은 (날짜, 배치)가 이미 있어 건너뛰었으면 거짓
     */
    boolean save(ForecastPublished published, ObservationBatchSeen seen);

    /**
     * 노선(모든 버전)의 실행을 수신 시각이 범위 안에 든 것만 이벤트 순서(수신 시각, 배치 번호)로 읽어 이벤트로 되살린다.
     * 복구용이다. 버스별 예측은 배치 안 행 번호 순이다.
     *
     * @param routeId 노선
     * @param fromInclusive 수신 시각 하한(포함)
     * @param toExclusive 수신 시각 상한(제외)
     * @return 이벤트 목록
     */
    List<ForecastPublished> findPublishedBetween(long routeId, Instant fromInclusive, Instant toExclusive);

    /**
     * 노선(모든 버전)의 실행 가운데 수신 시각이 범위 안인 것의 수집 사실을 이벤트 순서(수신 시각, 배치 번호)로 읽는다.
     * 복구용이다. 버스별 기록은 읽지 않는다.
     *
     * @param routeId 노선
     * @param fromInclusive 수신 시각 하한(포함)
     * @param toExclusive 수신 시각 상한(제외)
     * @return 배치별 수집 사실
     */
    List<RecordedBatch> findRecordedBatchesBetween(long routeId, Instant fromInclusive, Instant toExclusive);

    /**
     * 노선의 실행 가운데 미해결 기록({@code forecast_unresolved})이 저장된 예측을 가진 것 중 가장 늦게 수신한 것의 처리 시작 시각을 찾는다.
     * 정산이 그 노선을 저장까지 마친 이벤트 시각이며, 복구가 예보를 어디서부터 다시 읽을지 정하는 데 쓴다.
     *
     * @param routeId 노선
     * @param fromInclusive 수신 시각 하한(포함)
     * @param toExclusive 수신 시각 상한(제외)
     * @return 범위 안에 미해결 기록이 저장된 예측이 없으면 빈 값
     */
    Optional<Instant> findLastUnresolvedConfirmedAt(long routeId, Instant fromInclusive, Instant toExclusive);
}

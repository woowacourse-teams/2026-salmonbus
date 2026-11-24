package com.gustler.backend.api.board.infrastructure.jdbc;

import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.error.ServiceUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 예보 기록({@code forecast_run}, {@code forecast_record})을 탑승판의 저장 예측으로 읽는다.
 *
 * <p>버스당 한 행의 목표 정류장, 기대 좌석, 만석 확률 배열을 목표 정류장마다 한 예측으로 펼친다. 예보와 차량을 붙이는 키는
 * (목표 정류장, 배치 안 행 번호)다. 예보는 하차 반영 모델만 내므로 예측 목표는
 * {@value #SEAT_TARGET}, {@value #PROBABILITY_TARGET}로 고정이고, 모델은 실행 기록의 모델 배포다.
 * 예측을 실은 실행 기록({@code PUBLISHED})의 기록만 읽으며, 날짜 파티션 조건을 같이 걸어 해당 파티션만 읽는다.
 * 호출자의 반복 읽기 트랜잭션에 참여한다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcForecastRecordReader {

    /** 예보 쪽 {@code SeatForecastResult.ALIGHTING_AWARE_SEAT_TARGET}과 같은 값이다. */
    static final String SEAT_TARGET = "AFTER_ALIGHTING_ESTIMATE";
    /** 예보 쪽 {@code SeatForecastResult.ALIGHTING_AWARE_PROBABILITY_TARGET}과 같은 값이다. */
    static final String PROBABILITY_TARGET = "DEPARTURE_ZERO";

    private static final String PREDICTIONS_SQL = """
        SELECT /* salmonbus:board.forecast_record */ record.observation_batch_id, record.vehicle_id,
               record.source_row_number, record.passed_stop_order,
               target.target_stop_order, target.expected_seats, target.full_probability,
               model.id AS model_id, model.release_id, model.data_until
        FROM forecast_run run
        JOIN forecast_record record ON record.source_date=run.source_date
            AND record.observation_batch_id=run.observation_batch_id
        CROSS JOIN LATERAL unnest(record.target_stop_orders, record.expected_seats, record.full_probabilities)
            AS target(target_stop_order, expected_seats, full_probability)
        LEFT JOIN model_deployment model ON model.id=run.model_deployment_id
        WHERE run.source_date = ANY(CAST(:sourceDates AS date[]))
          AND record.source_date = ANY(CAST(:sourceDates AS date[]))
          AND run.observation_batch_id = ANY(:batchIds)
          AND record.observation_batch_id = ANY(:batchIds)
          AND run.outcome='PUBLISHED'
        ORDER BY record.observation_batch_id, record.source_row_number, target.target_stop_order
        """;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    /**
     * 관측 묶음마다 예측을 읽는다. 예측이 없는 묶음은 결과에 없다.
     *
     * @param sourceDateByBatchId 관측 묶음과 그 묶음의 날짜 파티션
     * @return 관측 묶음별 예측
     * @throws ServiceUnavailableException 예측을 낸 모델 배포를 찾을 수 없을 때
     */
    public Map<Long, List<StoredPrediction>> findPredictions(Map<Long, LocalDate> sourceDateByBatchId) {
        if (sourceDateByBatchId.isEmpty()) {
            return Map.of();
        }
        String[] sourceDates = sourceDateByBatchId.values().stream()
            .distinct()
            .map(LocalDate::toString)
            .toArray(String[]::new);
        Long[] batchIds = sourceDateByBatchId.keySet().toArray(Long[]::new);
        Map<Long, List<StoredPrediction>> predictions = new LinkedHashMap<>();
        jdbcClient.sql(PREDICTIONS_SQL)
            .param("sourceDates", sourceDates)
            .param("batchIds", batchIds)
            .query(row -> {
                predictions.computeIfAbsent(row.getLong("observation_batch_id"), ignored -> new ArrayList<>())
                    .add(predictionOf(row));
            });
        return predictions;
    }

    private StoredPrediction predictionOf(ResultSet row) throws SQLException {
        Long modelId = row.getObject("model_id", Long.class);
        if (modelId == null) {
            throw new ServiceUnavailableException();
        }
        final int targetStopOrder = row.getInt("target_stop_order");
        Double fullProbability = row.getObject("full_probability", Double.class);
        return new StoredPrediction(
                targetStopOrder,
                row.getString("vehicle_id"),
                row.getInt("source_row_number"),
                targetStopOrder - row.getInt("passed_stop_order"),
                probabilityOrNaN(fullProbability),
                row.getObject("expected_seats", Double.class),
                new ForecastModel(modelId, row.getString("release_id"), timeAt(row, "data_until")),
                SEAT_TARGET,
                PROBABILITY_TARGET
        );
    }

    private static double probabilityOrNaN(Double fullProbability) {
        if (fullProbability == null) {
            return Double.NaN;
        }
        return fullProbability;
    }

    private OffsetDateTime timeAt(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).atZoneSameInstant(clock.getZone()).toOffsetDateTime();
    }
}

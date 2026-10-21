package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.StopDemandMeasurement;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.infrastructure.statistics.StatisticsExtractWriter.Bundle;
import com.gustler.backend.forecasting.infrastructure.statistics.StatisticsExtractWriter.Manifest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 파일 계산 후 최종 통계만 게시한다. 자동 스케줄 및 원본 이관/삭제와는 별개의 경계다. */
final class StatisticsFilePublisher {
    private static final int MAX_INPUT_ROWS = 10_000;
    private final JdbcClient jdbc;
    private final RouteDataQualityAccess quality;
    private final StopDemandStatisticsRepository statistics;
    private final Clock clock;
    private final TransactionTemplate transaction;

    StatisticsFilePublisher(
        JdbcClient jdbc,
        PlatformTransactionManager transactions,
        RouteDataQualityAccess quality,
        StopDemandStatisticsRepository statistics,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.statistics = statistics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    int publish(Bundle bundle, Map<String, Integer> capacities, final int maxDailyCells) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("파일 읽기와 계산은 DB 트랜잭션 밖에서 실행해야 한다");
        }
        if (!hasValidInput(bundle, capacities)) {
            throw new IllegalArgumentException("검증 가능한 입력 묶음과 차량 정원이 필요하다");
        }

        Manifest manifest = bundle.manifest();
        StatisticsInputScope scope = manifest.scope();
        if (scope.dataUntil().isAfter(clock.instant())) {
            throw new IllegalArgumentException("현재 시각 이후의 통계는 게시하지 않는다");
        }

        TreeMap<String, Integer> fixedCapacities = new TreeMap<>(capacities);
        String digest = digest(new Input(manifest, fixedCapacities));
        // DB 연결을 보유하지 않은 상태에서 무결성 검사와 계산을 끝낸다.
        Set<EvaluationKey> inputKeys = readKeys(bundle);
        List<StopDemandMeasurement> measurements =
            StatisticsFileCalculator.calculate(bundle.directory(), manifest.files(),
                scope, fixedCapacities, maxDailyCells, 64 * 1024);
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout='500ms'").update();
            jdbc.sql("SET LOCAL lock_timeout='100ms'").update();
            final long revision = quality.lock(scope.routeVersionId());
            if (revision != scope.qualityRevision() || quality.anyInvestigationPending(scope.routeVersionId())) {
                throw new IllegalStateException("계산 입력의 품질 판본이 현재 게시 기준과 다르다");
            }
            Optional<Integer> previous = jdbc.sql("""
                SELECT revision FROM file_statistics_publication
                WHERE route_version_id=:route AND input_sha256=:digest
                """).param("route", scope.routeVersionId()).param("digest", digest).query(Integer.class).optional();
            if (previous.isPresent()) {
                return previous.get();
            }
            final boolean newer = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM demand_statistics_version
                    WHERE route_version_id=:route AND calculation_version=:calculation
                      AND quality_revision=:quality AND data_until>:until)
                """).param("route", scope.routeVersionId()).param("calculation", scope.calculationVersion())
                .param("quality", revision).param("until", OffsetDateTime.ofInstant(scope.dataUntil(), ZoneOffset.UTC))
                .query(Boolean.class).single();
            if (newer) {
                throw new IllegalStateException("더 최근 자료의 통계가 이미 게시되어 있다");
            }
            assertComplete(scope, inputKeys);
            final int published =
                Math.incrementExact(statistics.currentRevision(scope.routeVersionId(), scope.calculationVersion()));
            statistics.append(new DemandStatisticsVersion(scope.routeVersionId(), scope.calculationVersion(),
                published, scope.dataUntil(), clock.instant(), measurements));
            jdbc.sql("""
                INSERT INTO file_statistics_publication(route_version_id,input_sha256,calculation_version,revision)
                VALUES(:route,:digest,:calculation,:revision)
                """).param("route", scope.routeVersionId()).param("digest", digest)
                .param("calculation", scope.calculationVersion()).param("revision", published).update();
            return published;
        });
    }

    private boolean hasValidInput(Bundle bundle, Map<String, Integer> capacities) {
        if (bundle == null || capacities == null) {
            return false;
        }
        Manifest manifest = bundle.manifest();
        if (manifest == null) {
            return false;
        }
        return "statistics-extract-v1".equals(manifest.format());
    }

    private static Set<EvaluationKey> readKeys(Bundle bundle) {
        if (bundle.manifest().scope().rowCount() > MAX_INPUT_ROWS) {
            throw new IllegalArgumentException("소량 파일 게시 범위를 넘는 자료는 전체 재집계 경로를 사용해야 한다");
        }
        Set<EvaluationKey> keys = new HashSet<>();
        try (StatisticsInputReader reader = new StatisticsInputReader(bundle.directory(), bundle.manifest()
            .files(), 64 * 1024)) {
            while (reader.hasNext()) {
                StatisticsInputRow row = reader.next();
                if (!keys.add(new EvaluationKey(row.predictionObservationId(), row.targetStopOrder()))
                    || keys.size() > MAX_INPUT_ROWS) {
                    throw new IllegalArgumentException("입력 정산 키가 중복되거나 소량 파일 게시 범위를 넘는다");
                }
            }
        }
        return keys;
    }

    private void assertComplete(StatisticsInputScope scope, Set<EvaluationKey> inputKeys) {
        final boolean archived = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM evaluation_archive_batch
                WHERE route_version_id=:route AND storage_state='PURGED')
            """).param("route", scope.routeVersionId()).query(Boolean.class).single();
        if (archived) {
            throw new IllegalStateException("S3 이력이 있는 노선은 RDS와 S3를 함께 읽는 전체 재집계 경로를 사용해야 한다");
        }
        List<EvaluationKey> currentKeys = jdbc.sql("""
            SELECT vehicle_observation_id,target_stop_order FROM forecast_evaluation_result
            WHERE route_version_id=:route AND scored_at<=:until
            ORDER BY vehicle_observation_id,target_stop_order LIMIT :limit
            """).param("route", scope.routeVersionId())
            .param("until", OffsetDateTime.ofInstant(scope.dataUntil(), ZoneOffset.UTC))
            .param("limit", MAX_INPUT_ROWS + 1)
            .query((rs, rowNum) -> new EvaluationKey(rs.getLong(1), rs.getInt(2))).list();
        if (!inputKeys.equals(new HashSet<>(currentKeys))) {
            throw new IllegalStateException("파일이 기준 시각까지의 전체 정산을 포함하지 않아 통계를 게시하지 않는다");
        }
    }

    private record EvaluationKey(long observationId, int targetStopOrder) {
    }

    private static String digest(Input input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JsonMapper.builder().build().writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", exception);
        }
    }

    private record Input(Manifest manifest, Map<String, Integer> capacities) {
        /** 호출자가 가진 원본과 분리해 null·원소·순서를 보존한다. */
        private Input {
            if (capacities != null) {
                capacities = Collections.unmodifiableMap(new LinkedHashMap<>(capacities));
            }
        }
    }
}

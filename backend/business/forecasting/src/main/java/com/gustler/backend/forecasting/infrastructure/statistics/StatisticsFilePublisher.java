package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 파일 계산 후 최종 통계만 게시한다. 자동 스케줄 및 원본 이관/삭제와는 별개의 경계다. */
final class StatisticsFilePublisher {
    private final JdbcClient jdbc;
    private final RouteDataQualityAccess quality;
    private final StopDemandStatisticsRepository statistics;
    private final Clock clock;
    private final TransactionTemplate transaction;

    StatisticsFilePublisher(JdbcClient jdbc, PlatformTransactionManager transactions,
        RouteDataQualityAccess quality, StopDemandStatisticsRepository statistics, Clock clock) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.statistics = statistics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    int publish(StatisticsExtractWriter.Bundle bundle, Map<String, Integer> capacities, int maxDailyCells) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("파일 읽기와 계산은 DB 트랜잭션 밖에서 실행해야 한다");
        }
        if (bundle == null || capacities == null || bundle.manifest() == null
            || !"statistics-extract-v1".equals(bundle.manifest().format())) {
            throw new IllegalArgumentException("검증 가능한 입력 묶음과 차량 정원이 필요하다");
        }
        var scope = bundle.manifest().scope();
        if (scope.dataUntil().isAfter(clock.instant())) {
            throw new IllegalArgumentException("현재 시각 이후의 통계는 게시하지 않는다");
        }
        var fixedCapacities = new TreeMap<>(capacities);
        String digest = digest(new Input(bundle.manifest(), fixedCapacities));
        // DB 연결을 보유하지 않은 상태에서 무결성 검사와 계산을 끝낸다.
        var measurements = StatisticsFileCalculator.calculate(bundle.directory(), bundle.manifest().files(),
            scope, fixedCapacities, maxDailyCells, 64 * 1024);
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout='500ms'").update();
            jdbc.sql("SET LOCAL lock_timeout='100ms'").update();
            long revision = quality.lock(scope.routeVersionId());
            if (revision != scope.qualityRevision() || quality.anyInvestigationPending(scope.routeVersionId())) {
                throw new IllegalStateException("계산 입력의 품질 판본이 현재 게시 기준과 다르다");
            }
            var previous = jdbc.sql("""
                SELECT revision FROM file_statistics_publication
                WHERE route_version_id=:route AND input_sha256=:digest
                """).param("route", scope.routeVersionId()).param("digest", digest).query(Integer.class).optional();
            if (previous.isPresent()) {
                return previous.get();
            }
            boolean newer = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM demand_statistics_version
                    WHERE route_version_id=:route AND calculation_version=:calculation
                      AND quality_revision=:quality AND data_until>:until)
                """).param("route", scope.routeVersionId()).param("calculation", scope.calculationVersion())
                .param("quality", revision).param("until", OffsetDateTime.ofInstant(scope.dataUntil(), ZoneOffset.UTC))
                .query(Boolean.class).single();
            if (newer) {
                throw new IllegalStateException("더 최근 자료의 통계가 이미 게시되어 있다");
            }
            int published = Math.incrementExact(statistics.currentRevision(scope.routeVersionId(), scope.calculationVersion()));
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

    private static String digest(Input input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JsonMapper.builder().build().writeValueAsBytes(input)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", exception);
        }
    }

    private record Input(StatisticsExtractWriter.Manifest manifest, Map<String, Integer> capacities) { }
}

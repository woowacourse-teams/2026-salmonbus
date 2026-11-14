package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새 예보 경로의 날짜 파티션 테이블 다섯 개에 한국 날짜 기준 오늘부터 {@value #DAYS_AHEAD}일 뒤까지 {@code <table>_pYYYYMMDD}
 * 파티션을 만든다.
 *
 * <p>파티션 하나는 짧은 트랜잭션 하나로 만든다. PostgreSQL은 파티션을 만드는 동안 부모 테이블과 기본 파티션에
 * {@code ACCESS EXCLUSIVE} 잠금을 잡고 기본 파티션을 훑으므로, 잠금 대기를 3초로 묶어 쓰기를 오래 막지 않는다.
 * 여러 인스턴스가 동시에 만들면 한쪽이 "이미 있음"으로 실패할 수 있어, 실패하면 다시 확인하고 있으면 성공으로 본다.
 * 테이블 소유자가 아니라 권한이 없거나, 기본 파티션에 그 날짜의 줄이 이미 있거나, 잠금을 얻지 못하면 예외를 던지지 않고 경고 로그와
 * {@code salmonbus.pipeline.partition.failures} 지표만 남긴다. 그 날짜의 줄은 기본 파티션으로 들어간다.
 *
 * <p>테이블 이름은 고정 목록에서만 오므로 SQL에 그대로 넣는다.
 */
@Component
public class PartitionMaintenance implements MaintainPipelinePartitions {
    /** 날짜 파티션을 두는 테이블. 기본 파티션은 {@code <table>_default}다. */
    public static final List<String> TABLES =
        List.of("forecast_run", "forecast_record", "stop_pass", "pass_label", "forecast_unresolved");

    /** 오늘에 더해 미리 만드는 날 수. */
    public static final int DAYS_AHEAD = 3;

    private static final String LOCK_TIMEOUT = "3s";
    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionMaintenance.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Map<String, AtomicLong> defaultRowGauges = new ConcurrentHashMap<>();
    private MeterRegistry meters;

    public PartitionMaintenance(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 실패 수와 기본 파티션 줄 수를 지표로 낸다. 지표 등록기가 없으면 로그만 남긴다. */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public Report maintain() {
        final LocalDate today = SourceDates.of(clock.instant());
        List<String> created = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String table : TABLES) {
            for (int day = 0; day <= DAYS_AHEAD; day++) {
                final LocalDate date = today.plusDays(day);
                switch (ensure(table, date)) {
                    case CREATED -> created.add(partitionName(table, date));
                    case FAILED -> failed.add(partitionName(table, date));
                    case EXISTING -> {
                    }
                }
            }
        }
        Map<String, Long> defaultRows = new LinkedHashMap<>();
        for (String table : TABLES) {
            try {
                final long rows = countDefaultRows(table);
                defaultRows.put(table, rows);
                if (rows > 0) {
                    LOGGER.warn("event=pipeline_partition_default_rows table={} rows={}", table, rows);
                }
            } catch (DataAccessException failure) {
                LOGGER.warn("event=pipeline_partition_default_count_failed table={} sqlState={}", table,
                    sqlState(failure));
            }
        }
        if (!created.isEmpty()) {
            LOGGER.info("event=pipeline_partitions_created partitions={}", created);
        }
        return new Report(created, failed, defaultRows);
    }

    /**
     * 테이블의 한 날짜 파티션이 없으면 만든다. 실패해도 예외를 던지지 않는다.
     *
     * @param table {@link #TABLES} 가운데 하나
     * @param date 파티션 날짜
     * @return 이번에 만들었는지, 이미 있었는지, 만들지 못했는지
     */
    public Outcome ensure(String table, LocalDate date) {
        requireKnown(table);
        String partition = partitionName(table, date);
        if (isPartitionOf(partition, table)) {
            return Outcome.EXISTING;
        }
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL lock_timeout = '%s'".formatted(LOCK_TIMEOUT)).update();
                jdbc.sql("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
                    .formatted(partition, table, date, date.plusDays(1))).update();
            });
        } catch (DataAccessException failure) {
            if (isPartitionOf(partition, table)) {
                return Outcome.EXISTING;
            }
            return failed(table, partition, reasonOf(sqlState(failure)), sqlState(failure));
        }
        if (!isPartitionOf(partition, table)) {
            return failed(table, partition, "name_taken", null);
        }
        if (meters != null) {
            meters.counter("salmonbus.pipeline.partition.created", "table", table).increment();
        }
        return Outcome.CREATED;
    }

    /**
     * 테이블의 기본 파티션에 들어 있는 줄 수를 세고 {@code salmonbus.pipeline.partition.default.rows} 지표에 반영한다.
     *
     * @param table {@link #TABLES} 가운데 하나
     * @return 기본 파티션의 줄 수
     */
    public long countDefaultRows(String table) {
        requireKnown(table);
        final long rows = jdbc.sql("SELECT /* salmonbus:pipeline_partition.count_default */ count(*) FROM %s_default"
            .formatted(table)).query(Long.class).single();
        if (meters != null) {
            defaultRowGauges.computeIfAbsent(table, name -> meters.gauge(
                "salmonbus.pipeline.partition.default.rows", Tags.of("table", name), new AtomicLong()))
                .set(rows);
        }
        return rows;
    }

    /** 테이블과 날짜의 파티션 이름. 예: {@code forecast_run_p20261115}. */
    public static String partitionName(String table, LocalDate date) {
        return table + "_p" + date.format(SUFFIX);
    }

    private boolean isPartitionOf(String partition, String table) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM pg_inherits
                    WHERE inhrelid = to_regclass(?) AND inhparent = to_regclass(?))
                """)
            .params(partition, table)
            .query(Boolean.class)
            .single();
    }

    private Outcome failed(String table, String partition, String reason, String sqlState) {
        LOGGER.warn("event=pipeline_partition_create_failed table={} partition={} reason={} sqlState={}", table,
            partition, reason, sqlState);
        if (meters != null) {
            meters.counter("salmonbus.pipeline.partition.failures", "table", table, "reason", reason).increment();
        }
        return Outcome.FAILED;
    }

    private static String reasonOf(String sqlState) {
        if (sqlState == null) {
            return "other";
        }
        return switch (sqlState) {
            case "42501" -> "permission";
            case "23514" -> "default_rows";
            case "55P03" -> "lock_timeout";
            default -> "other";
        };
    }

    private static String sqlState(DataAccessException failure) {
        return failure.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static void requireKnown(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("파티션을 관리하지 않는 테이블이다: " + table);
        }
    }

    /** 파티션 하나를 확인한 결과. */
    public enum Outcome {
        /** 이번에 만들었다. */
        CREATED,
        /** 이미 있었다. 다른 인스턴스가 먼저 만든 경우를 포함한다. */
        EXISTING,
        /** 만들지 못했다. 그 날짜의 줄은 기본 파티션으로 들어간다. */
        FAILED
    }
}

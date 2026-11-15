package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.forecast.MaintainPartitions;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 날짜 파티션 테이블에 한국 날짜 기준 오늘부터 {@value #DAYS_AHEAD}일 뒤까지 {@code <table>_pYYYYMMDD} 파티션을 만든다.
 *
 * <p>대상은 두 묶음이다. 예보 기록 테이블 다섯 개({@link #TABLES})는 예보가 켜졌을 때만 다룬다({@link #setForecastTables}).
 * 관측 두 테이블({@link #OBSERVATION_TABLES})은 카탈로그에서 파티션 테이블({@code relkind='p'})일 때만 다룬다. 수동 SQL
 * ({@code deploy/sql/sal434-04-partition-observation-tables.sql})이 같은 이름 규칙({@code <table>_pYYYYMMDD}, 기본 파티션
 * {@code <table>_default}, 하루 범위 {@code [날짜, 날짜+1)})으로 이 두 테이블을 바꾸기 전에는 일반 테이블이라 카탈로그만 읽고
 * 아무것도 쓰지 않는다.
 *
 * <p>파티션 하나는 짧은 트랜잭션 하나로 만든다. PostgreSQL은 파티션을 만드는 동안 부모 테이블과 기본 파티션에
 * {@code ACCESS EXCLUSIVE} 잠금을 잡고 기본 파티션을 훑으므로, 잠금 대기를 3초로 묶어 쓰기를 오래 막지 않는다.
 * 여러 인스턴스가 동시에 만들면 한쪽이 "이미 있음"으로 실패할 수 있어, 실패하면 다시 확인하고 있으면 성공으로 본다.
 * 테이블 소유자가 아니라 권한이 없거나, 기본 파티션에 그 날짜의 줄이 이미 있거나, 잠금을 얻지 못하면 예외를 던지지 않고 경고 로그와
 * {@code salmonbus.partition.failures} 지표만 남긴다. 그 날짜의 줄은 기본 파티션으로 들어간다.
 *
 * <p>테이블 이름은 고정 목록에서만 오므로 SQL에 그대로 넣는다.
 */
@Component
public class PartitionMaintenance implements MaintainPartitions {
    /** 날짜 파티션을 두는 테이블. 기본 파티션은 {@code <table>_default}다. */
    public static final List<String> TABLES =
        List.of("forecast_run", "forecast_record", "stop_pass", "pass_label", "forecast_unresolved");

    /** 파티션 테이블일 때만 다루는 관측 테이블. 기본 파티션은 {@code <table>_default}다. */
    public static final List<String> OBSERVATION_TABLES = List.of("observation_batch", "vehicle_observation");

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
    private boolean forecastTables = true;

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

    /**
     * 예보 기록 테이블 다섯 개를 다룰지 정한다. 예보가 켜졌을 때({@code forecast.enabled=true}) 참이며, 직접 만든 객체는 참이다.
     *
     * @param forecastTables 예보 기록 테이블을 다루면 참
     */
    @Value("${forecast.enabled:false}")
    public void setForecastTables(final boolean forecastTables) {
        this.forecastTables = forecastTables;
    }

    /** 이번에 다룰 테이블. 예보 기록 테이블(예보가 켜졌을 때)과 파티션 테이블인 관측 테이블이다. */
    public List<String> managedTables() {
        List<String> tables = new ArrayList<>();
        if (forecastTables) {
            tables.addAll(TABLES);
        }
        for (String table : OBSERVATION_TABLES) {
            if (isPartitioned(table)) {
                tables.add(table);
            }
        }
        return tables;
    }

    @Override
    public Report prepare() {
        return create(managedTables());
    }

    @Override
    public Report maintain() {
        List<String> tables = managedTables();
        Report created = create(tables);
        return new Report(created.created(), created.failed(), defaultRowsOf(tables));
    }

    private Report create(List<String> tables) {
        LocalDate today = SourceDates.of(clock.instant());
        List<String> created = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        ensurePartitions(tables, today, created, failed);
        if (!created.isEmpty()) {
            LOGGER.info("event=partitions_created partitions={}", created);
        }
        return new Report(created, failed, Map.of());
    }

    /** 테이블마다 오늘부터 {@value #DAYS_AHEAD}일 뒤까지 파티션을 확인하고, 만든 것과 실패한 것의 이름을 각 목록에 더한다. */
    private void ensurePartitions(
        List<String> tables,
        LocalDate today,
        List<String> created,
        List<String> failed
    ) {
        for (String table : tables) {
            for (int day = 0; day <= DAYS_AHEAD; day++) {
                LocalDate date = today.plusDays(day);
                switch (ensure(table, date)) {
                    case CREATED -> created.add(partitionName(table, date));
                    case FAILED -> failed.add(partitionName(table, date));
                    case EXISTING -> {
                    }
                }
            }
        }
    }

    /** 기본 파티션 줄 수를 테이블 순서대로 센다. 세지 못한 테이블은 경고 로그만 남기고 결과에서 뺀다. */
    private Map<String, Long> defaultRowsOf(List<String> tables) {
        Map<String, Long> defaultRows = new LinkedHashMap<>();
        for (String table : tables) {
            try {
                final long rows = countDefaultRows(table);
                defaultRows.put(table, rows);
                if (rows > 0) {
                    LOGGER.warn("event=partition_default_rows table={} rows={}", table, rows);
                }
            } catch (DataAccessException failure) {
                LOGGER.warn("event=partition_default_count_failed table={} sqlState={}", table,
                    sqlState(failure));
            }
        }
        return defaultRows;
    }

    /**
     * 테이블의 한 날짜 파티션이 없으면 만든다. 실패해도 예외를 던지지 않는다.
     *
     * @param table {@link #TABLES} 또는 {@link #OBSERVATION_TABLES} 가운데 하나. 관측 테이블은 파티션 테이블이어야 한다
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
                jdbc.sql("CREATE /* salmonbus:partition.create */ TABLE IF NOT EXISTS %s PARTITION OF %s "
                    .formatted(partition, table)
                    + "FOR VALUES FROM ('%s') TO ('%s')".formatted(date, date.plusDays(1))).update();
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
            meters.counter("salmonbus.partition.created", "table", table).increment();
        }
        return Outcome.CREATED;
    }

    /**
     * 테이블의 기본 파티션에 들어 있는 줄 수를 세고 {@code salmonbus.partition.default.rows} 지표에 반영한다.
     *
     * @param table {@link #TABLES} 또는 {@link #OBSERVATION_TABLES} 가운데 하나
     * @return 기본 파티션의 줄 수
     */
    public long countDefaultRows(String table) {
        requireKnown(table);
        final long rows = jdbc.sql("SELECT /* salmonbus:partition.count_default */ count(*) FROM %s_default"
            .formatted(table)).query(Long.class).single();
        if (meters != null) {
            defaultRowGauges.computeIfAbsent(table, name -> meters.gauge(
                "salmonbus.partition.default.rows", Tags.of("table", name), new AtomicLong()))
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
                SELECT /* salmonbus:partition.is_attached */ EXISTS (SELECT 1 FROM pg_inherits
                    WHERE inhrelid = to_regclass(?) AND inhparent = to_regclass(?))
                """)
            .params(partition, table)
            .query(Boolean.class)
            .single();
    }

    private boolean isPartitioned(String table) {
        return jdbc.sql("""
                SELECT /* salmonbus:partition.is_partitioned */ EXISTS (
                    SELECT 1 FROM pg_class WHERE oid = to_regclass(?) AND relkind = 'p')
                """)
            .param(table)
            .query(Boolean.class)
            .single();
    }

    private Outcome failed(String table, String partition, String reason, String sqlState) {
        LOGGER.warn("event=partition_create_failed table={} partition={} reason={} sqlState={}", table,
            partition, reason, sqlState);
        if (meters != null) {
            meters.counter("salmonbus.partition.failures", "table", table, "reason", reason).increment();
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
        if (failure.getMostSpecificCause() instanceof SQLException sql) {
            return sql.getSQLState();
        }
        return null;
    }

    private static void requireKnown(String table) {
        if (!TABLES.contains(table) && !OBSERVATION_TABLES.contains(table)) {
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

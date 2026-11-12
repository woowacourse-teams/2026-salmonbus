package com.gustler.backend.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/**
 * V66을 실행하는 트랜잭션에서만 {@code enable_hashjoin}과 {@code enable_mergejoin}을 끈다.
 *
 * <p>V66의 작은 정산 대기 작업이 대규모 예측 테이블 전체를 읽는 계획을 피한다. 이미 V66을 적용한 DB의 체크섬을 지키려고 SQL 파일은
 * 고치지 않고 실행 연결의 설정으로 계획을 바꾼다. 다른 마이그레이션의 계획은 바꾸지 않도록 V66 하나로 한정한다.
 *
 * <p>{@code set_config(..., true)}는 현재 트랜잭션 안에서만 유효하다. 그래서 자동 커밋 연결에서는 V66을 시작하지 않고 멈춘다.
 * V66이 성공하면 같은 트랜잭션에서 원래 값으로 되돌린다. Flyway 가 여러 마이그레이션을 한 트랜잭션으로 묶어도 뒤 마이그레이션은 바뀐
 * 설정을 물려받지 않는다. V66이 실패하면 트랜잭션 롤백이 설정을 함께 되돌리므로 기억한 원래 값만 버린다.
 */
@Component
public final class QualityRetirementMigrationCallback implements Callback {
    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("66");
    private final Map<Connection, PlanSettings> previous = new ConcurrentHashMap<>();

    @Override
    public boolean supports(Event event, Context context) {
        return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE_ERROR)
            && context.getMigrationInfo() != null && VERSION.equals(context.getMigrationInfo().getVersion());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        if (event == Event.AFTER_EACH_MIGRATE_ERROR) {
            previous.remove(connection);
            return;
        }
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                if (connection.getAutoCommit()) {
                    throw new FlywayException("V66 query plan settings require a migration transaction");
                }
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT current_setting('enable_hashjoin'), current_setting('enable_mergejoin')")) {
                    result.next();
                    previous.put(connection, new PlanSettings(result.getString(1), result.getString(2)));
                }
                apply(connection, new PlanSettings("off", "off"));
                return;
            }
            PlanSettings settings = previous.remove(connection);
            if (settings != null) {
                apply(connection, settings);
            }
        } catch (SQLException failure) {
            previous.remove(connection);
            throw new FlywayException("Cannot apply V66 migration query plan settings", failure);
        }
    }

    private static void apply(Connection connection, PlanSettings settings) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('enable_hashjoin', ?, true), set_config('enable_mergejoin', ?, true)")) {
            statement.setString(1, settings.hashJoin());
            statement.setString(2, settings.mergeJoin());
            statement.execute();
        }
    }

    @Override
    public String getCallbackName() {
        return "qualityRetirementMigrationQueryPlan";
    }

    private record PlanSettings(String hashJoin, String mergeJoin) { }
}

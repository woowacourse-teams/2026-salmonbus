package com.gustler.backend.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 마이그레이션 하나를 실행하는 동안 그 연결의 소켓 읽기 제한을 {@code migration.network-timeout}으로 바꾸고, 끝나면 원래 값으로 되돌린다.
 *
 * <p>풀의 {@code socketTimeout}보다 오래 걸리는 마이그레이션 문장은 서버가 실행 중이어도 연결이 먼저 끊긴다. 이 콜백은 Flyway 가
 * 마이그레이션에 쓰는 연결에만 적용하고 다른 풀 연결은 바꾸지 않는다. {@code BEFORE_MIGRATE}·{@code AFTER_MIGRATE}는 Flyway 가
 * 별도 연결로 실행하므로 쓰지 않는다.
 *
 * <p>실패 이벤트에서는 원래 마이그레이션 오류를 가리지 않도록 되돌리기 실패를 던지지 않는다. 그 연결은 풀에 반납될 때 HikariCP 가
 * 풀 기본값으로 되돌린다.
 */
@Component
public final class MigrationNetworkTimeoutCallback implements Callback {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationNetworkTimeoutCallback.class);
    private static final Executor CALLER = Runnable::run;

    private final int timeoutMillis;
    private final Map<Connection, Integer> previous = new ConcurrentHashMap<>();

    MigrationNetworkTimeoutCallback(@Value("${migration.network-timeout}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("migration.network-timeout 은 0보다 커야 한다: " + timeout);
        }
        this.timeoutMillis = Math.toIntExact(timeout.toMillis());
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE_ERROR;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        if (event == Event.BEFORE_EACH_MIGRATE) {
            extend(connection);
            return;
        }
        restore(connection, event == Event.AFTER_EACH_MIGRATE_ERROR);
    }

    private void extend(Connection connection) {
        try {
            previous.putIfAbsent(connection, connection.getNetworkTimeout());
            connection.setNetworkTimeout(CALLER, timeoutMillis);
        } catch (SQLException failure) {
            previous.remove(connection);
            throw new FlywayException("Cannot extend the network timeout of the migration connection", failure);
        }
    }

    private void restore(Connection connection, final boolean failed) {
        Integer original = previous.remove(connection);
        if (original == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.setNetworkTimeout(CALLER, original);
            }
        } catch (SQLException failure) {
            if (!failed) {
                throw new FlywayException("Cannot restore the network timeout of the migration connection", failure);
            }
            LOGGER.warn("event=migration_network_timeout_restore status=FAILED cause={}",
                failure.getClass().getSimpleName());
        }
    }

    @Override
    public String getCallbackName() {
        return "migrationNetworkTimeout";
    }
}

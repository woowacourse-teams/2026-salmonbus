package com.gustler.backend.routeoperations.infrastructure.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 알림 전용 연결 하나를 유지한다. 구독을 커밋한 뒤 전체 상태를 읽어 연결 공백을 복구한다. */
public final class RouteOperationListener implements SmartLifecycle {
    static final String CHANNEL = "salmonbus_route_operations";
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteOperationListener.class);
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(5).toNanos();

    private final DataSource dataSource;
    private final JdbcRouteOperations database;
    private final RouteOperationCache cache;
    private volatile boolean running;
    private volatile int backendPid;
    private Thread thread;

    public RouteOperationListener(
        DataSource dataSource,
        JdbcRouteOperations database,
        RouteOperationCache cache
    ) {
        this.dataSource = dataSource;
        this.database = database;
        this.cache = cache;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (thread != null && thread.isAlive()) {
            throw new IllegalStateException("ROUTE_OPERATIONS_LISTENER_STOPPING");
        }
        cache.invalidate();
        running = true;
        thread = new Thread(this::run, "route-operations-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (running) {
            try {
                listenOnce();
            } catch (SQLException | RuntimeException failure) {
                cache.invalidate();
                if (running) {
                    LOGGER.warn("event=route_operations_listener_disconnected exceptionType={}",
                        failure.getClass().getSimpleName());
                }
            }
            retryDelay();
        }
    }

    private void listenOnce() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            connection.setNetworkTimeout(Runnable::run, 5000);
            try {
                subscribe(connection);
                cache.replaceAll(database.readSnapshot(connection));
                PGConnection postgres = connection.unwrap(PGConnection.class);
                backendPid = postgres.getBackendPID();
                receive(connection, postgres);
            } finally {
                cache.invalidate();
                backendPid = 0;
                unsubscribe(connection);
            }
        }
    }

    private void subscribe(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("LISTEN " + CHANNEL);
        }
    }

    private void receive(Connection connection, PGConnection postgres) throws SQLException {
        long verifiedAt = System.nanoTime();
        while (running) {
            PGNotification[] notifications = postgres.getNotifications(1000);
            if (notifications != null) {
                for (PGNotification notification : notifications) {
                    refresh(connection, notification);
                }
            }
            if (System.nanoTime() - verifiedAt >= HEARTBEAT_NANOS) {
                verifyConnection(connection);
                verifiedAt = System.nanoTime();
            }
        }
    }

    private void refresh(Connection connection, PGNotification notification) throws SQLException {
        if (!CHANNEL.equals(notification.getName())) {
            return;
        }
        String routeId = notification.getParameter();
        if (routeId.isEmpty()) {
            cache.replaceAll(database.readSnapshot(connection));
            return;
        }
        if (!routeId.matches("[0-9]{9}")) {
            LOGGER.warn("event=route_operations_notification_ignored reason=INVALID_ROUTE_ID");
            return;
        }
        cache.replace(routeId, database.readSnapshot(connection, routeId));
    }

    private void verifyConnection(Connection connection) throws SQLException {
        if (!connection.isValid(2)) {
            throw new SQLException("ROUTE_OPERATIONS_CONNECTION_LOST");
        }
        cache.verified();
    }

    private void unsubscribe(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("UNLISTEN " + CHANNEL);
        } catch (SQLException failure) {
            LOGGER.debug("event=route_operations_unlisten_failed exceptionType={}",
                failure.getClass().getSimpleName());
        }
    }

    private void retryDelay() {
        if (!running) {
            return;
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        cache.invalidate();
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(6500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOGGER.warn("event=route_operations_listener_stop_pending");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int backendPid() {
        return backendPid;
    }
}

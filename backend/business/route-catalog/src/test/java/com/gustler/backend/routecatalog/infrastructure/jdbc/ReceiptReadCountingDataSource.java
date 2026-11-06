package com.gustler.backend.routecatalog.infrastructure.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Observes actual JDBC execution and returned body bytes without replacing the PostgreSQL query. */
final class ReceiptReadCountingDataSource extends DelegatingDataSource {
    private final Map<String, Integer> reads = new ConcurrentHashMap<>();
    private final Map<String, Long> bodyBytes = new ConcurrentHashMap<>();

    ReceiptReadCountingDataSource(DataSource delegate) { super(delegate); }
    int reads(String receiptId) { return reads.getOrDefault(receiptId, 0); }
    long bodyBytes(String receiptId) { return bodyBytes.getOrDefault(receiptId, 0L); }
    void resetCounts() { reads.clear(); bodyBytes.clear(); }

    @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        return observe(super.getConnection(user, password));
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                Object result = invoke(method, connection, args);
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                        && result instanceof PreparedStatement statement) {
                    String normalized = sql.toLowerCase(Locale.ROOT);
                    if (normalized.contains("route_source_receipt") && normalized.contains("route_source_blob"))
                        return observe(statement);
                }
                return result;
            });
    }

    private PreparedStatement observe(PreparedStatement statement) {
        String[] receiptId = {null};
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                Object result = invoke(method, statement, args);
                if ((method.getName().equals("setString") || method.getName().equals("setObject"))
                        && Integer.valueOf(1).equals(args[0])) receiptId[0] = String.valueOf(args[1]);
                if (method.getName().startsWith("execute") && receiptId[0] != null)
                    reads.merge(receiptId[0], 1, Integer::sum);
                if (result instanceof ResultSet rows) return observe(rows, receiptId[0]);
                return result;
            });
    }

    private ResultSet observe(ResultSet rows, String receiptId) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> {
                Object result = invoke(method, rows, args);
                if (method.getName().equals("getBytes") && result instanceof byte[] bytes && receiptId != null)
                    bodyBytes.merge(receiptId, (long) bytes.length, Long::sum);
                return result;
            });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failed) { throw failed.getCause(); }
    }
}

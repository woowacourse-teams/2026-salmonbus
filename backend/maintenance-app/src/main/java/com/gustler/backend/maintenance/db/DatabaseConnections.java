package com.gustler.backend.maintenance.db;

import com.gustler.backend.maintenance.DatabaseEnvironment;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 검증된 유지보수 DB 설정으로 연결 공급자를 구성한다.
 * 대상 선택과 승인 판단은 호출 경계에서 먼저 수행한다.
 */
public final class DatabaseConnections {
    private DatabaseConnections() {
    }

    public static DataSource dataSource(DatabaseEnvironment environment) {
        DriverManagerDataSource source = new DriverManagerDataSource(
            environment.jdbcUrl(), environment.username(), environment.password());
        Properties properties = new Properties();
        properties.setProperty("ApplicationName", "salmonbus-historical-migration");
        source.setConnectionProperties(properties);
        return source;
    }
}

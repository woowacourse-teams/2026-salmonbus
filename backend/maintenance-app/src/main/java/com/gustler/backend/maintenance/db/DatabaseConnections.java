package com.gustler.backend.maintenance.db;

import com.gustler.backend.maintenance.DatabaseEnvironment;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class DatabaseConnections {

    private DatabaseConnections() {
    }

    public static DataSource dataSource(DatabaseEnvironment environment) {
        DriverManagerDataSource source = new DriverManagerDataSource(
            environment.jdbcUrl(), environment.username(), environment.password());
        Properties properties = new Properties();
        properties.setProperty("ApplicationName", "salmonbus-quality-maintenance");
        source.setConnectionProperties(properties);
        return source;
    }
}

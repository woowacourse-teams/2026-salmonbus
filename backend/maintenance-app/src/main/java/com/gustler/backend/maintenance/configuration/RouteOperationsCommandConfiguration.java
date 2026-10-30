package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.routeoperations.configuration.RouteOperationsConfiguration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public final class RouteOperationsCommandConfiguration {
    private RouteOperationsCommandConfiguration() {
    }

    public static RouteOperations create(DataSource dataSource) {
        return RouteOperationsConfiguration.create(
            JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource));
    }
}

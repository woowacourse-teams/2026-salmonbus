package com.gustler.backend.routeoperations.configuration;

import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.routeoperations.infrastructure.jdbc.CachedRouteOperations;
import com.gustler.backend.routeoperations.infrastructure.jdbc.JdbcRouteOperations;
import com.gustler.backend.routeoperations.infrastructure.jdbc.RouteOperationCache;
import com.gustler.backend.routeoperations.infrastructure.jdbc.RouteOperationListener;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt in once at deployment; individual changes subsequently require no restart. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "route-operations", name = "enabled", havingValue = "true")
public class RouteOperationsConfiguration {
    @Bean
    JdbcRouteOperations jdbcRouteOperations(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcRouteOperations(jdbc, transactions);
    }

    @Bean
    @Primary
    RouteOperations routeOperations(JdbcRouteOperations database, ObjectProvider<RouteOperationCache> caches) {
        RouteOperationCache cache = caches.getIfAvailable();
        if (cache == null) {
            return database;
        }
        return new CachedRouteOperations(database, cache);
    }

    @Bean
    @ConditionalOnProperty(prefix = "route-operations", name = "notifications-enabled",
        havingValue = "true", matchIfMissing = true)
    RouteOperationCache routeOperationCache() {
        return new RouteOperationCache();
    }

    @Bean
    @ConditionalOnProperty(prefix = "route-operations", name = "notifications-enabled",
        havingValue = "true", matchIfMissing = true)
    RouteOperationListener routeOperationListener(
        DataSource dataSource,
        JdbcRouteOperations database,
        RouteOperationCache cache
    ) {
        return new RouteOperationListener(dataSource, database, cache);
    }

    public static RouteOperations create(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcRouteOperations(jdbc, transactions);
    }
}

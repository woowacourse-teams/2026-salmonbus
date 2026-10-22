package com.gustler.backend.support;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 컨텍스트마다 JVM 공유 PostgreSQL 서버 안에 빈 DB 를 하나 만들어 연결한다.
 *
 * <p>컨텍스트마다 새 DB 라 데이터 격리는 컨텍스트마다 서버를 띄우던 때와 같다. 서버 컨테이너는 빈으로 내보내지 않는다.
 * 빈으로 내보내면 컨텍스트가 닫힐 때 Spring 이 {@code close} 로 공유 서버를 멈춘다. 서버를 멈춰야 하는 시험은
 * {@link DedicatedPostgresTestContainer} 를 쓴다.
 *
 * <p>DataSource 가 연결 정보 빈을 거쳐 DB 빈에 의존하므로, 컨텍스트가 닫힐 때 연결 풀이 먼저 닫히고 DB 를 지운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {
    @Bean
    TestDatabase postgresTestDatabase() {
        return TestDatabase.create();
    }

    @Bean
    JdbcConnectionDetails postgresConnectionDetails(TestDatabase database) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return database.username();
            }

            @Override
            public String getPassword() {
                return database.password();
            }

            @Override
            public String getJdbcUrl() {
                return database.jdbcUrl();
            }
        };
    }
}

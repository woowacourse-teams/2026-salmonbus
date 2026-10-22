package com.gustler.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 컨텍스트 하나가 독점하는 PostgreSQL 서버. 시험이 서버를 멈춰 DB 장애를 만들 때만 쓴다.
 *
 * <p>공유 서버({@link PostgresTestContainer})를 멈추면 같은 JVM 의 다른 컨텍스트가 모두 끊긴다. 이 설정은 컨테이너를 빈으로
 * 내보내 시험이 주입받아 멈출 수 있게 하고, 컨텍스트가 닫힐 때 컨테이너도 멈춘다. {@link PostgresTestContainer} 와 함께
 * 가져오면 연결 정보가 둘이 되므로 따로 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DedicatedPostgresTestContainer {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(SharedPostgres.IMAGE);
    }
}

package com.gustler.backend.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트 JVM 하나가 함께 쓰는 PostgreSQL 서버.
 *
 * <p>처음 요청할 때 띄우고 직접 멈추지 않는다. JVM 이 끝나면 Testcontainers 의 Ryuk 가 지운다. 시험은 이 서버 안에
 * {@link TestDatabase} 로 자기 DB 를 만들어 쓰므로 데이터는 DB 단위로 격리된다. 서버를 멈추거나 서버 단위 설정을 바꾸는
 * 시험은 이 서버를 쓰지 않는다.
 */
final class SharedPostgres {
    static final String IMAGE = "postgres:18";

    private static PostgreSQLContainer server;

    private SharedPostgres() {
    }

    static synchronized PostgreSQLContainer server() {
        if (server == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(IMAGE);
            started.start();
            server = started;
        }
        return server;
    }
}

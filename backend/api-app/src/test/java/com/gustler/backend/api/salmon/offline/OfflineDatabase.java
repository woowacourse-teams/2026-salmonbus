package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.support.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 실행 한 번이 쓰는 로컬 임시 PostgreSQL DB. 제품 마이그레이션으로 스키마를 올리고, 닫을 때 DB를 지운다.
 * 테스트용 공유 컨테이너 안에 만들며 운영 DB에 접속하지 않는다.
 */
final class OfflineDatabase implements AutoCloseable {
    private final TestDatabase database;
    private final SingleConnectionDataSource dataSource;

    OfflineDatabase() {
        database = TestDatabase.create();
        Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password()).load().migrate();
        dataSource = new SingleConnectionDataSource(database.jdbcUrl(), database.username(), database.password(),
            true);
    }

    JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** CSV의 문자열 값을 DB가 열 타입으로 읽게 하는 적재 전용 연결이다. */
    Connection loadingConnection() throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl() + "?stringtype=unspecified&reWriteBatchedInserts=true",
            database.username(), database.password());
    }

    @Override
    public void close() {
        dataSource.destroy();
        database.close();
    }
}

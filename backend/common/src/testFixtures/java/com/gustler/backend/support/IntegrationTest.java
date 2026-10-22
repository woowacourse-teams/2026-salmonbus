package com.gustler.backend.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 실행 앱 전체를 띄우고 컨텍스트마다 새 DB 에 연결한다.
 *
 * <p>컨텍스트들이 한 PostgreSQL 서버의 연결 수를 나눠 쓰므로 미리 열어 두는 연결은 하나로 둔다. 최대 풀 크기는 그대로다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.datasource.hikari.minimum-idle=1")
@Import(PostgresTestContainer.class)
public @interface IntegrationTest {
}

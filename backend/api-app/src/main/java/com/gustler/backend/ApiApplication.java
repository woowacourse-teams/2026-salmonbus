package com.gustler.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 공개 조회 API의 실행 진입점이다.
 * 수집·예보 쓰기 작업은 별도 Worker 실행 경계에 둔다.
 */
@SpringBootApplication
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}

package com.gustler.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 공개 조회 API의 실행 진입점이다.
 * 수집·예보 쓰기 작업은 별도 Worker 실행 경계에 둔다.
 * 로그인만 예외로, 회원 모듈의 공개 계약({@code member.api})으로 회원을 저장한다.
 */
@SpringBootApplication
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}

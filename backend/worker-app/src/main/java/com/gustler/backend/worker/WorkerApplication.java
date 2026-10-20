package com.gustler.backend.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 수집·예보·정산·통계를 수행하는 Worker 실행 진입점이다.
 * 공개 클라이언트 API는 별도 실행 앱에 둔다.
 */
@SpringBootApplication
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}

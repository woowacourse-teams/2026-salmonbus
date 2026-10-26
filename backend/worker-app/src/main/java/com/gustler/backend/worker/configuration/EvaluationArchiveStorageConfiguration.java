package com.gustler.backend.worker.configuration;

import com.gustler.backend.worker.archive.S3EvaluationArchiveObjects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** S3 전송 구현과 수명주기는 실제 저장소를 사용하는 worker가 소유한다. */
@Configuration(proxyBeanMethods = false)
public class EvaluationArchiveStorageConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    S3EvaluationArchiveObjects evaluationArchiveObjects() {
        return S3EvaluationArchiveObjects.create();
    }
}

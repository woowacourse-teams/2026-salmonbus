package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.api.evaluation.LesEvaluationArchive;
import com.gustler.backend.forecasting.application.evaluation.LesEvaluationArchiveService;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcLesEvaluationArchiveStore;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** 정산 결과 한 건의 복원만 연결한다. 직접 @Import할 때만 등록하며 예약 실행과 삭제는 구성하지 않는다. */
public class LesEvaluationRestoreConfiguration {
    @Bean
    LesEvaluationArchive lesEvaluationArchive(JdbcClient jdbc, EvaluationArchiveObjects objects,
        PlatformTransactionManager transactions) {
        return new LesEvaluationArchiveService(new JdbcLesEvaluationArchiveStore(jdbc, transactions), objects, false,
            1);
    }
}

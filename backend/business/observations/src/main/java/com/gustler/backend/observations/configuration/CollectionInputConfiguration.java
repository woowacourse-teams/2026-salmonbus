package com.gustler.backend.observations.configuration;

import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.infrastructure.jdbc.JdbcCollectionInputs;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 정비 앱이 명시적으로 가져오는 JDBC 구성. Worker의 컴포넌트 검색에는 등록하지 않는다. */
public class CollectionInputConfiguration {
    @Bean
    CollectionInputs collectionInputs(JdbcClient jdbc) {
        return new JdbcCollectionInputs(jdbc);
    }
}

package com.gustler.backend.routecatalog.configuration;

import com.gustler.backend.routecatalog.application.StopWalkDistanceLoader;
import com.gustler.backend.routecatalog.infrastructure.file.StopWalkDatasetFileReader;
import com.gustler.backend.routecatalog.infrastructure.jdbc.JdbcStopWalkDistanceStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 정류장 쌍 도보 거리 파일을 DB에 넣는 구성 요소만 등록한다. 노선 카탈로그의 GBIS·JPA 구성은 가져오지 않는다.
 * 쓰는 쪽이 {@code JdbcClient}, {@code JdbcTemplate}, 트랜잭션 관리자를 준비한다.
 */
@Configuration(proxyBeanMethods = false)
@Import({StopWalkDistanceLoader.class, JdbcStopWalkDistanceStore.class, StopWalkDatasetFileReader.class})
public class StopWalkDistanceImportConfiguration {
}

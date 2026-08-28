package com.gustler.backend.collector.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 이름 앞의 Collector 는 조회 쪽에 같은 표를 보는 리포지터리가 따로 있어서 붙였다.
 * Spring Data 인터페이스는 빈이고 빈 이름이 단순 클래스 이름에서 나온다.
 * 이름이 겹치면 BeanDefinitionOverrideException 으로 앱이 안 뜬다.
 */
public interface CollectorObservationBatchRepository extends JpaRepository<ObservationBatchJpaEntity, Long> {
}

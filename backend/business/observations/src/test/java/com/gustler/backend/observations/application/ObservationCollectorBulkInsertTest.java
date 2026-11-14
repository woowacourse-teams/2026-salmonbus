package com.gustler.backend.observations.application;

import org.springframework.test.context.TestPropertySource;

/**
 * 관측 한 문장 저장을 켠 채로 수집 통합 시나리오 전체를 다시 돌린다.
 * 정류장 FK 위반의 롤백·사건·진단 로그와 같은 계획 재시도가 꺼짐과 같게 끝나는지 확인한다.
 */
@TestPropertySource(properties = "collection.bulk-insert=true")
class ObservationCollectorBulkInsertTest extends ObservationCollectorTest {
}

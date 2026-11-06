package com.gustler.backend.routecatalog.domain.walking;

import java.time.OffsetDateTime;

/**
 * 정류장 쌍 도보 거리를 저장한다. 현재 자료는 마지막으로 적재한 묶음 하나뿐이고, 적재 기록도 그 묶음 한 행만 남긴다.
 */
public interface StopWalkDistanceStore {
    /**
     * 지금 테이블에 들어 있는 거리가 이 지문의 묶음인지 본다. 예전에 적재했다가 다른 묶음으로 바뀐 지문은 false다.
     */
    boolean isCurrent(String checksum);

    /**
     * 기존 거리와 적재 기록을 모두 지우고 묶음의 거리와 기록으로 바꾼다. 한 트랜잭션에서 실행해 중간 상태를 남기지 않는다.
     */
    void replace(StopWalkDataset dataset, OffsetDateTime loadedAt);
}

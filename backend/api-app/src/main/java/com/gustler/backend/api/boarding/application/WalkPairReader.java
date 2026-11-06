package com.gustler.backend.api.boarding.application;

import com.gustler.backend.api.boarding.domain.WalkPair;
import java.util.List;

/**
 * 정적 묶음에서 적재한 정류장 쌍 도보 거리를 읽는다.
 */
public interface WalkPairReader {
    /**
     * 정류장이 한쪽에 들어간 도보 거리 쌍을 모두 읽는다.
     */
    List<WalkPair> walkPairs(String stationId);
}

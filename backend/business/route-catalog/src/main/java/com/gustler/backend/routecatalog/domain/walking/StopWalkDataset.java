package com.gustler.backend.routecatalog.domain.walking;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 한 번의 일괄 계산으로 만든 정류장 쌍 도보 거리 묶음이다. 같은 정류장 쌍은 한 번만 들어 있다.
 *
 * @param checksum 묶음 파일의 SHA-256. 같은 묶음을 다시 적재하지 않는 기준이다
 * @param osmBase 계산에 쓴 OSM 자료의 기준 시각
 */
public record StopWalkDataset(String checksum, OffsetDateTime osmBase, List<StopWalkDistance> pairs) {
    public StopWalkDataset {
        Objects.requireNonNull(checksum, "checksum은 null일 수 없습니다.");
        Objects.requireNonNull(osmBase, "osmBase는 null일 수 없습니다.");
        if (!checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum은 소문자 16진수 64자여야 한다");
        }
        pairs = List.copyOf(pairs);
        Set<String> seen = new HashSet<>();
        for (StopWalkDistance pair : pairs) {
            if (!seen.add(pair.stationA() + ":" + pair.stationB())) {
                throw new IllegalArgumentException("같은 정류장 쌍이 두 번 들어 있다: " + pair.stationA() + "-" + pair.stationB());
            }
        }
    }
}

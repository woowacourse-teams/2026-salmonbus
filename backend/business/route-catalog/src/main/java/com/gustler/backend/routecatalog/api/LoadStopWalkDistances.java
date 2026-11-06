package com.gustler.backend.routecatalog.api;

import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * 레포 밖에서 만든 정류장 쌍 도보 거리 파일을 DB에 넣는 공개 업무 경계다.
 */
public interface LoadStopWalkDistances {
    /**
     * 파일을 검증하고, 지금 적재된 묶음과 다르면 한 트랜잭션에서 통째로 바꾼다. 같으면 쓰지 않는다.
     *
     * @param distances 정류장 쌍 도보 거리 {@code csv.gz}
     * @param metadata 같은 묶음의 메타데이터 {@code json}({@code osmBase}·{@code sha256}·{@code pairCount})
     */
    Result load(Path distances, Path metadata, OffsetDateTime loadedAt);

    /**
     * 적재 결과와 적재 대상 묶음의 지문·쌍 수.
     */
    record Result(Status status, String checksum, int pairCount) {
    }

    enum Status {
        LOADED, UNCHANGED
    }
}

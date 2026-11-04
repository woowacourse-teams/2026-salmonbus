package com.gustler.backend.forecasting.application.evaluation;

import java.util.List;
import java.util.Optional;

/** Les 이관의 DB 경계. 원격 파일 전송이나 공개 유스케이스를 실행하지 않는다. */
public interface LesEvaluationArchiveStore {
    record Key(
        long id,
        int stop,
        String target,
        String version,
        long revision
    ) {
    }

    record Row(String originalJson, String evidenceJson) {
    }

    Optional<String> verifiedForPurge();

    List<Row> candidates(int limit);

    void archive(List<Row> verifiedRows, String objectHash, boolean deleteEnabled);

    String objectHash(Key key);

    int restore(Key key, String objectHash, String originalJson);
}

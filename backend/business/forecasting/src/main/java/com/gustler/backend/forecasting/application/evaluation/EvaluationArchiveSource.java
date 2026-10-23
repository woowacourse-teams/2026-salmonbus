package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;

/** DB 검증 기록과 파일의 전체 내용을 대조한 원본만 반환한다. */
public interface EvaluationArchiveSource {
    /**
     * 검증된 아카이브 배치의 원본 행을 읽는다. 외부 저장소 접근을 포함하므로 DB 트랜잭션 범위를 호출자가 조율한다.
     */
    List<Row> readOriginal(EvaluationArchiveBatch verified);
}

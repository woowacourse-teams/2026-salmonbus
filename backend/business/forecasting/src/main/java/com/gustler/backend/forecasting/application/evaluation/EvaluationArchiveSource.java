package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;

/** DB 검증 기록과 파일의 전체 내용을 대조한 원본만 반환한다. */
public interface EvaluationArchiveSource {
    List<Row> readOriginal(EvaluationArchiveBatch verified);
}

package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;

public interface EvaluationArchiveStorage {
    /** 원본과 설명 파일 모두를 저장한 뒤 다시 받아 검증한다. */
    Verification storeAndVerify(EvaluationArchiveBatch batch, List<Row> rows);

    record Verification(String location, String manifestSha256) { }
}

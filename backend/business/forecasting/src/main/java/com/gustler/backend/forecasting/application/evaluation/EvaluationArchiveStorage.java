package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import java.util.List;

/**
 * 예약한 정산 원문을 파일 경계에 저장하고 다시 읽어 동일성을 검증한다.
 * 검증 없이 DB 원본을 삭제하지 않는다.
 */
public interface EvaluationArchiveStorage {
    /** 원본과 설명 파일 모두를 저장한 뒤 다시 받아 검증한다. */
    Verification storeAndVerify(EvaluationArchiveBatch batch, List<Row> rows);

    record Verification(String location, String manifestSha256) {
    }
}

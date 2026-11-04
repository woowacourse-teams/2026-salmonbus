package com.gustler.backend.forecasting.api.evaluation;

/** 기존 정수 정산 v1과 구분되는 Les 목표별 이관/복원 경계. */
public interface LesEvaluationArchive {
    void advance();

    int restore(
        long observationId,
        int stop,
        String target,
        String labelVersion,
        long qualityRevision);
}

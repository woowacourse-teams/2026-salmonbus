package com.gustler.backend.forecasting.infrastructure.statistics;

import java.util.Iterator;

/** 전체 파일을 키순으로 합친 후보를 읽어 검증한다. 자료 자체는 메모리에 보관하지 않는다. */
final class StatisticsInputRows {

    private StatisticsInputRows() {
    }

    static void verify(StatisticsInputScope scope, Iterator<StatisticsInputRow> rows) {
        if (scope == null || rows == null) {
            throw new IllegalArgumentException("입력 범위와 자료가 필요하다");
        }
        long count = 0;
        StatisticsInputRow previous = null;
        while (rows.hasNext()) {
            StatisticsInputRow row = rows.next();
            if (row == null) {
                throw new IllegalArgumentException("비어 있는 자료가 포함됐다");
            }
            if (previous != null) {
                int order = Long.compare(row.predictionObservationId(), previous.predictionObservationId());
                if (order == 0) {
                    order = Integer.compare(row.targetStopOrder(), previous.targetStopOrder());
                }
                if (order == 0) {
                    throw new IllegalArgumentException("같은 관측의 같은 목표 정류장이 중복됐다");
                }
                if (order < 0) {
                    throw new IllegalArgumentException("자료가 정산 키 순서로 정렬되지 않았다");
                }
            }
            if (count == scope.rowCount()) {
                throw new IllegalArgumentException("자료 건수가 설명보다 많다");
            }
            row.sample(scope);
            count++;
            previous = row;
        }
        if (count != scope.rowCount()) {
            throw new IllegalArgumentException("자료 건수가 설명보다 적다");
        }
    }
}

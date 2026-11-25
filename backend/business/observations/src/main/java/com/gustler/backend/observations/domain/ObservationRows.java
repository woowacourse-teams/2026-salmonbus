package com.gustler.backend.observations.domain;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 정규화한 관측을 저장 가능한 행과 제외할 행으로 구분한 결과. */
public record ObservationRows(
    List<VehicleObservationRow> storableRows,
    List<VehicleObservationRow> excludedRows
) {

    /** 원문 대신 보존하는 관측에 적용한 정규화 규칙의 버전. */
    public static final String CURRENT_NORMALIZATION_VERSION = "normalization-v1.0.0";

    public ObservationRows {
        storableRows = List.copyOf(storableRows);
        excludedRows = List.copyOf(excludedRows);
    }

    public static ObservationRows fromObservations(List<VehicleObservation> buses) {
        Map<Boolean, List<VehicleObservationRow>> rowsByStorable = IntStream.range(0, buses.size())
            .mapToObj(rowNumber -> new VehicleObservationRow(rowNumber, buses.get(rowNumber)))
            .collect(Collectors.partitioningBy(row -> row.observation().isStorable()));

        return new ObservationRows(rowsByStorable.get(true), rowsByStorable.get(false));
    }

    /** 저장 대상에서 제외한 행을 포함한 GBIS 응답의 행 수. */
    public int providerRows() {
        return storableRowCount() + excludedRowCount();
    }

    public int storableRowCount() {
        return storableRows.size();
    }

    public int excludedRowCount() {
        return excludedRows.size();
    }

    /** 정상적인 빈 응답은 전체 제외로 보지 않는다. */
    public boolean isFullyExcluded() {
        return storableRows.isEmpty() && !excludedRows.isEmpty();
    }

    /** 저장할 수 없는 행의 좌석은 품질 판정 대상으로 전달하지 않는다. */
    public boolean hasPositiveSeats() {
        return storableRows.stream()
            .map(VehicleObservationRow::observation)
            .anyMatch(VehicleObservation::hasPositiveSeats);
    }
}

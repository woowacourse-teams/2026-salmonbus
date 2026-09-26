package com.gustler.backend.observations.domain;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 정규화한 관측을 저장 가능한 행과 제외할 행으로 구분한 결과. */
public record CollectedObservations(
    List<UpstreamObservationRow> storableRows,
    List<UpstreamObservationRow> excludedRows
) {

    /** 원문 대신 보존하는 관측에 적용한 정규화 규칙의 버전. */
    public static final String CURRENT_NORMALIZATION_VERSION = "normalization-v1.0.0";

    public CollectedObservations {
        storableRows = List.copyOf(storableRows);
        excludedRows = List.copyOf(excludedRows);
    }

    public static CollectedObservations fromObservations(
        List<VehicleObservation> buses
    ) {
        Map<Boolean, List<UpstreamObservationRow>> rowsByStorable = IntStream.range(0, buses.size())
            .mapToObj(rowNumber -> toRow(rowNumber, buses.get(rowNumber)))
            .collect(Collectors.partitioningBy(CollectedObservations::isStorable));

        return new CollectedObservations(rowsByStorable.get(true), rowsByStorable.get(false));
    }

    /** 저장 대상에서 제외한 행을 포함한 상류 응답의 행 수. */
    public int providerRows() {
        return storableRows.size() + excludedRows.size();
    }

    /**
     * 필수값이 모두 있어 저장할 수 있는 관측인지 확인한다.
     *
     * <p>필수값이 하나라도 없으면 해당 행의 INSERT가 실패한다. 수집 배치와 관측은 같은 트랜잭션에
     * 저장되므로 배치 전체가 롤백된다. 차량 하나의 누락으로 나머지 열여섯 차량의 관측과
     * 수집 완료 기록까지 함께 사라진다.
     */
    private static boolean isStorable(
        UpstreamObservationRow row
    ) {
        return row.observation().hasKnownStop()
            && row.observation().hasKnownRunningState();
    }

    private static UpstreamObservationRow toRow(
        final int rowNumber,
        VehicleObservation bus
    ) {
        return new UpstreamObservationRow(rowNumber, bus);
    }
}

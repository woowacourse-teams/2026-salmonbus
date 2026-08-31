package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 한 판에서 상류가 준 차량을 쌓을 것과 뺀 것으로 가른 결과.
 *
 * <p>운행 상태의 뜻을 모르는 차량은 그 행만 뺀다. 한 대 때문에 그 판을 통째로 버리지 않는다.
 * 상류가 모르는 값을 줄 때 수집을 세우지 않는 것이 이 표의 규칙이고,
 * station_id 에 FK 를 걸지 않은 것도 같은 이유다.
 */
public record CollectedObservations(
    List<UpstreamObservationRow> storableRows,
    List<UpstreamObservationRow> excludedRows
) {

    /**
     * 지금 도는 정규화 규칙의 판. 규칙이 바뀌면 올린다.
     * 응답 원문을 따로 안 남겨서 관측이 사실상 원본이고, 이 값이 있어야
     * 나중에 어느 규칙으로 접힌 값인지 되짚는다.
     */
    public static final String CURRENT_NORMALIZATION_VERSION = "normalization-v1.0.0";

    public CollectedObservations {
        storableRows = List.copyOf(storableRows);
        excludedRows = List.copyOf(excludedRows);
    }

    public static CollectedObservations from(
        List<BusLocation> buses
    ) {
        Map<Boolean, List<UpstreamObservationRow>> rowsByStorable = IntStream.range(0, buses.size())
            .mapToObj(rowNumber -> toRow(rowNumber, buses.get(rowNumber)))
            .collect(Collectors.partitioningBy(CollectedObservations::isStorable));

        return new CollectedObservations(rowsByStorable.get(true), rowsByStorable.get(false));
    }

    /** 상류가 준 행 수. 뺀 행까지 센다. */
    public int providerRows() {
        return storableRows.size() + excludedRows.size();
    }

    /**
     * 쌓을 수 있는 관측인가. 저장할 때 비울 수 없는 값이 다 있어야 한다.
     *
     * <p>하나라도 비면 그 행의 INSERT 가 거절되는데, 묶음과 관측이 한 트랜잭션이라
     * 그 판이 통째로 되감긴다. 차 하나 때문에 나머지 열여섯의 관측과
     * "수집했다" 는 기록까지 같이 사라진다.
     */
    private static boolean isStorable(
        UpstreamObservationRow row
    ) {
        return row.observation().hasKnownStop()
            && row.observation().hasKnownRunningState();
    }

    private static UpstreamObservationRow toRow(
        final int rowNumber,
        BusLocation bus
    ) {
        return new UpstreamObservationRow(rowNumber, VehicleObservation.from(bus));
    }
}

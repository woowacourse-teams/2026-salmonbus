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

    public CollectedObservations {
        storableRows = List.copyOf(storableRows);
        excludedRows = List.copyOf(excludedRows);
    }

    public static CollectedObservations from(
        List<BusLocation> buses
    ) {
        Map<Boolean, List<UpstreamObservationRow>> rowsByStorable = IntStream.range(0, buses.size())
            .mapToObj(rowNumber -> toRow(rowNumber, buses.get(rowNumber)))
            .collect(Collectors.partitioningBy(row -> row.observation().hasKnownRunningState()));

        return new CollectedObservations(rowsByStorable.get(true), rowsByStorable.get(false));
    }

    /** 상류가 준 행 수. 뺀 행까지 센다. */
    public int providerRows() {
        return storableRows.size() + excludedRows.size();
    }

    private static UpstreamObservationRow toRow(
        final int rowNumber,
        BusLocation bus
    ) {
        return new UpstreamObservationRow(rowNumber, VehicleObservation.from(bus));
    }
}

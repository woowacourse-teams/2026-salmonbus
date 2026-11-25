package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * 예보 단계가 배치 하나를 처리한 결과다. 상태는 다음 배치에 넘길 상태이며, 상태를 바꾸지 않는 결과에서는 받은 상태 그대로다.
 * 스냅샷은 예보할 차량의 입력이고, 뺀 차량은 사유와 같이 따로 담는다. 연결 시작을 보지 못해 통과로 만들지 않은 이벤트는 수만 센다.
 */
public record ForecastStageResult(
    Status status,
    String reason,
    RouteForecastState state,
    List<AlightingAwareFeatureSnapshot> snapshots,
    List<ClosedPass> closedPasses,
    int unattributedPasses,
    List<WithheldVehicle> withheld
) {

    public ForecastStageResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(state);
        snapshots = List.copyOf(snapshots);
        closedPasses = List.copyOf(closedPasses);
        withheld = List.copyOf(withheld);
    }

    static ForecastStageResult stateOnly(Status status, String reason, RouteForecastState state) {
        return new ForecastStageResult(
                status,
                reason,
                state,
                List.of(),
                List.of(),
                0,
                List.of()
        );
    }

    /** 처리 결과의 종류다. */
    public enum Status {

        /** 상태를 전진시켰다. 스냅샷은 비어 있을 수 있다. */
        FORECAST,
        /** 확정 조건을 만족하지 않아 상태와 순위를 바꾸지 않았다. */
        NOT_CONFIRMED,
        /** 이미 반영한 배치보다 늦지 않아 상태를 바꾸지 않았다. */
        STALE,
        /** 상태에 관측은 넣었지만 통과 판정이 실패해 예보하지 않았다. */
        INPUT_UNAVAILABLE,
        /** 입력이 불완전해 이 상태로 이어 갈 수 없다. 호출한 쪽은 상태를 버리고 복구해야 한다. */
        REPLAY_REQUIRED
    }

    /** 예보에서 뺀 차량 관측과 사유다. */
    public record WithheldVehicle(long observationId, String vehicleId, String reason) {

    }
}

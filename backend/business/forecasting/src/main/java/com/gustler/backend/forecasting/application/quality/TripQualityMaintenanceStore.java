package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch.Row;
import com.gustler.backend.forecasting.domain.quality.TripQualityDiscovery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 조사 범위의 미리보기·발견 커서·차량별 대기를 저장하고 조회한다.
 */
public interface TripQualityMaintenanceStore {
    record Batch(long id, Instant observedAt) {
    }
    record Signal(long batchId, Row row) {
    }
    /**
     * 지정 시각까지의 품질 유지보수 영향을 조회한다. 실제 품질 변경이나 조사 시작은 수행하지 않는다.
     */
    TripQualityPreview preview(long version, Instant until);

    /**
     * 노선의 저장된 발견 작업을 찾는다. 시작하지 않은 경우 빈 값이다.
     */
    Optional<TripQualityDiscovery> discovery(long version);

    /**
     * 지정 노선의 발견 작업 상한과 최초 커서를 저장한다. 실제 관측 변경은 후속 처리에서 수행한다.
     */
    void startDiscovery(long version, TripQualityDiscovery discovery);

    /**
     * 발견 작업의 고정 상한과 커서에 따라 최대 limit건의 묶음을 읽는다.
     */
    List<Batch> discoveryPage(long version, TripQualityDiscovery discovery, int limit);

    /**
     * 지정 관측 묶음에 있는 조사 시작 신호를 읽는다. 원천 API를 재호출하지 않는다.
     */
    List<Signal> anomalies(List<Long> batchIds);

    /**
     * 발견 작업의 커서와 완료 상태를 함께 저장한다.
     */
    void saveDiscovery(long version, TripQualityDiscovery discovery);

    /**
     * 유지보수에서 다음에 진행할 미완료 조사 차량을 찾는다. 대상이 없으면 빈 값이다.
     */
    Optional<String> nextPendingVehicle(long version);

    boolean hasPendingVehicles(long version);

    /**
     * 노선의 품질 조사 상태를 읽는다. 조사 작업 자체를 진행하지 않는다.
     */
    List<TripQualityStatus> status(long version);
}

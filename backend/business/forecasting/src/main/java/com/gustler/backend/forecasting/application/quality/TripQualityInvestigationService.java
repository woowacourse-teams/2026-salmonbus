package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripAssessment;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch.Row;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigationRepository;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigationRepository.Key;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 노선 품질 잠금 아래 조사 페이지를 읽어 도메인 조사 상태와 편도 판정을 함께 저장한다.
 */
@Service
public class TripQualityInvestigationService {
    private final TripQualityInvestigationRepository investigations;

    private final TripQualityStore store;
    private final RouteDataQualityAccess quality;
    private final RouteDataQualityChanges changes;
    private WorkerQualityTransactions memory;

    public TripQualityInvestigationService(
        TripQualityInvestigationRepository investigations,
        TripQualityStore store,
        RouteDataQualityAccess quality,
        RouteDataQualityChanges changes
    ) {
        this.investigations = investigations;
        this.store = store;
        this.quality = quality;
        this.changes = changes;
    }

    @Autowired(required = false)
    public void useQualityMemory(WorkerQualityTransactions memory) {
        this.memory = memory;
    }

    /**
     * 관측 저장 트랜잭션에서 새 이상 차량의 조사를 시작한다. 이미 조사 중인 차량은 중복 등록하지 않는다.
     */
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void observationsStored(QualityObservationBatch batch) {
        List<Row> anomalies = batch.firstAnomalies();
        if (anomalies.isEmpty()) {
            return;
        }
        if (memory != null) {
            memory.changing();
        }
        // 조사 완료와 새 이상 관측 저장을 직렬화한다. 조사 중의 동일 차량은 새 요청을 만들지 않는다.
        quality.lock(batch.routeVersionId());
        List<String> active = investigations.activeVehicleIds(batch.routeVersionId());
        Duration maximumGap = store.maximumObservationGap(batch.routeVersionId()).orElse(null);
        List<String> started = new ArrayList<>();
        for (Row anomaly : anomalies) {
            if (active.contains(anomaly.vehicleId())) {
                continue;
            }
            investigations.saveStart(TripQualityInvestigation.start(batch, anomaly, maximumGap));
            started.add(anomaly.vehicleId());
        }
        changes.vehiclesChanged(batch.routeVersionId(), started);
    }

    /**
     * 다음 조사 한 페이지를 제한된 트랜잭션에서 진행한다. 미완료 조사가 없으면 false이다.
     */
    @Transactional(readOnly = false, timeout = 2)
    public boolean investigateNext() {
        Optional<Key> pending = investigations.nextPending();
        if (pending.isEmpty()) {
            return false;
        }
        store.applyTimeBudget();
        investigateLocked(pending.get().routeVersionId(), pending.get().vehicleId());
        return true;
    }

    /**
     * 기존 트랜잭션에 참여해 품질 상태를 잠그고 조사 한 페이지를 저장한다. 자기 호출에 새 트랜잭션 생성을 기대하지 않는다.
     */
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void investigateLocked(final long version, String vehicle) {
        if (memory != null) {
            memory.changing();
        }
        quality.lock(version);
        Optional<TripQualityInvestigation> pending = investigations.findPending(version, vehicle);
        if (pending.isEmpty()) {
            return;
        }
        TripQualityInvestigation investigation = pending.get();
        Route route = store.readRoute(version);
        final boolean backwards = investigation.phase() == Phase.SEARCH_START;
        List<BatchObservations> rows =
            store.readPage(version, vehicle, investigation.cursorAt(), investigation.cursorBatchId(),
                backwards, investigation.includeCursor());
        investigatePage(investigation, route, rows, version, backwards);
        investigations.save(investigation);
        if (investigation.completed()) {
            changes.vehiclesChanged(version, List.of(vehicle));
        }
    }

    private void investigatePage(
        TripQualityInvestigation investigation,
        Route route,
        List<BatchObservations> rows,
        final long version,
        final boolean backwards
    ) {
        if (backwards) {
            investigation.searchStart(route, rows,
                store.observations(investigation.anchorObservationId(),
                    investigation.boundaryCandidateObservationId()));
            return;
        }
        Previous previous = readPrevious(investigation.previousObservationId());
        List<OneWayTripAssessment> assessments = investigation.replay(route, rows, previous);
        store.saveAssessments(version, assessments);
    }

    private Previous readPrevious(Long observationId) {
        if (observationId == null) {
            return null;
        }
        return store.previous(observationId);
    }
}

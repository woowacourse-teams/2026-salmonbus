package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.Fields.MissingReason;
import com.gustler.backend.api.salmon.domain.Fields.Observed;
import com.gustler.backend.api.salmon.domain.StopLists.Coverage;
import com.gustler.backend.api.salmon.domain.StopLists.Queried;
import com.gustler.backend.api.salmon.domain.StopLists.StopEntry;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.StopLists.Unavailable;
import com.gustler.backend.api.salmon.domain.Timing.Offset;
import com.gustler.backend.api.salmon.domain.Timing.Span;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/** 저장된 최신 도착 스냅샷에서 노선과 정류장 방문의 도착 목록을 만든다. 새로 수집을 요청하지 않는다. */
public final class ArrivalStopSource implements StopArrivalSource {
    private static final Span MINUTE_RESOLUTION_BOUND = new Span(60_000);

    public enum CoverageRule { NONE, SECOND_BEYOND_WINDOW }

    private final ArrivalReadRepository repository;
    private final CoverageRule coverageRule;
    private final ActionConfig config;

    public ArrivalStopSource(ArrivalReadRepository repository, CoverageRule coverageRule, ActionConfig config) {
        this.repository = repository;
        this.coverageRule = coverageRule;
        this.config = config;
    }

    @Override
    public StopList arrivalsAt(String routeId, String stationId, int stopSequence, Instant decisionAt) {
        try {
            return listOf(routeId, stationId, stopSequence, decisionAt);
        } catch (DataAccessException exception) {
            return new Unavailable(MissingReason.QUERY_FAILED);
        }
    }

    private StopList listOf(String routeId, String stationId, int stopSequence, Instant decisionAt) {
        Optional<ArrivalSnapshot> latest = repository.latest(stationId);
        if (latest.isEmpty()) {
            return new Unavailable(MissingReason.NOT_PROVIDED);
        }
        ArrivalSnapshot snapshot = latest.get();
        if (!decisionAt.isBefore(snapshot.staleAt())) {
            return new Unavailable(MissingReason.STALE);
        }
        Optional<ArrivalVisit> visit = repository.visits(stationId).stream()
            .filter(candidate -> candidate.routeId().equals(routeId) && candidate.stopSequence() == stopSequence)
            .findFirst();
        if (visit.isEmpty()) {
            return new Unavailable(MissingReason.UNVERIFIED_SEQUENCE);
        }
        Optional<RouteArrival> route = snapshot.routes().stream()
            .filter(candidate -> visit.get().matches(candidate.routeId(), candidate.sourceStopOrder(),
                snapshot.receivedAt()))
            .findFirst();
        List<StopEntry> entries = route.map(value -> value.arrivals().stream()
            .map(prediction -> entryOf(snapshot, routeId, stopSequence, prediction, decisionAt)).toList())
            .orElse(List.of());
        return new Queried(entries, coverageOf(entries));
    }

    private StopEntry entryOf(ArrivalSnapshot snapshot, String routeId, int stopSequence, Prediction prediction,
                              Instant decisionAt) {
        Instant expected = ArrivalPrediction.at(prediction, snapshot.receivedAt(), decisionAt).expectedArrivalAt();
        Offset arrival = new Offset(Duration.between(decisionAt, expected).toMillis());
        Optional<Span> quantization = prediction.sourceRemainingSeconds() == null
            ? Optional.of(MINUTE_RESOLUTION_BOUND) : Optional.empty();
        // 스냅샷은 정류장 단위라, 한 노선이 이 정류장을 두 번 지나면 방문 순번이 있어야 두 방문의 항목이 구분된다.
        String id = snapshot.id() + ":" + routeId + ":" + stopSequence + ":" + prediction.ordinal();
        Optional<String> vehicleId = Optional.ofNullable(prediction.vehicleId()).filter(value -> !value.isBlank());
        return new StopEntry(id, new Observed<>(arrival, quantization), vehicleId);
    }

    private Coverage coverageOf(List<StopEntry> entries) {
        if (coverageRule == CoverageRule.NONE || entries.size() != 2) {
            return Coverage.COVERAGE_UNKNOWN;
        }
        Offset latest = entries.stream().map(entry -> entry.arrival().present().orElseThrow())
            .max(Offset::compareTo).orElseThrow();
        return latest.compareTo(config.horizon()) > 0 ? Coverage.COVERS_WINDOW : Coverage.COVERAGE_UNKNOWN;
    }
}

package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import java.time.Duration;
import java.util.Objects;

final class ReverseBoundarySearch {
    private final Route route;
    private Observation anchor;
    private Observation candidate;
    private boolean boundaryConfirmed;

    ReverseBoundarySearch(Route route, Observation anchor, Observation candidate) {
        this.route = route;
        this.anchor = anchor;
        this.candidate = candidate != null ? candidate
            : OneWayTripClassifier.isDeparture(route, anchor) ? anchor : null;
    }

    void inspect(Observation older) {
        if (boundaryConfirmed || older == null) { return; }
        if (!Objects.equals(older.vehicleId(), anchor.vehicleId()) || older.at().isAfter(anchor.at())
            || Duration.between(older.at(), anchor.at()).compareTo(route.maximumGap()) > 0) {
            boundaryConfirmed = true;
            return;
        }
        if (isTerminal(older) && older.stopOrder() == anchor.stopOrder()) {
            if (OneWayTripClassifier.isDeparture(route, older)) { candidate = older; }
            anchor = older;
            return;
        }
        if (isTerminal(anchor) && candidate != null) {
            boundaryConfirmed = true;
            return;
        }
        int before = OneWayTripClassifier.direction(route, older);
        int after = OneWayTripClassifier.direction(route, anchor);
        boolean forward = anchor.stopOrder() >= older.stopOrder();
        boolean directionChange = before != after && (forward || (before == 1 && after == 0));
        // 회차지의 비출발 상태만으로 방향을 확정하지 않고, 앞선 출발 표시까지 확인한다.
        if (directionChange && isTerminal(older) && !OneWayTripClassifier.isDeparture(route, older)) {
            candidate = anchor;
            anchor = older;
            return;
        }
        boolean continuation = before == after && (forward || (before == 1
            && anchor.stopOrder() == route.firstStop() && !OneWayTripClassifier.isDeparture(route, anchor)));
        if (!continuation) {
            boundaryConfirmed = true;
            return;
        }
        anchor = older;
        if (OneWayTripClassifier.isDeparture(route, older)) { candidate = older; }
    }

    Observation anchor() { return anchor; }
    Observation candidate() { return candidate; }
    Observation replayStart() { return candidate == null ? anchor : candidate; }
    boolean boundaryConfirmed() { return boundaryConfirmed; }

    private boolean isTerminal(Observation observation) {
        return observation.stopOrder() == route.firstStop()
            || Objects.equals(observation.stopOrder(), route.turnStop());
    }
}

package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import java.time.Duration;
import java.util.Objects;

/**
 * 이상 관측에서 과거 방향으로 출발 근거를 탐색한다.
 * 방문 순서나 관측 간격이 끊기면 확인한 경계에서 멈춘다.
 */
final class ReverseBoundarySearch {
    private final Route route;
    private Observation anchor;
    private Observation candidate;
    private boolean boundaryConfirmed;

    ReverseBoundarySearch(Route route, Observation anchor, Observation candidate) {
        this.route = route;
        this.anchor = anchor;
        this.candidate = initialCandidate(route, anchor, candidate);
    }

    void inspect(Observation older) {
        if (boundaryConfirmed || older == null) {
            return;
        }
        if (isDisconnectedFromAnchor(older)) {
            boundaryConfirmed = true;
            return;
        }
        if (isRepeatedTerminalObservation(older)) {
            if (OneWayTripClassifier.isDeparture(route, older)) {
                candidate = older;
            }
            anchor = older;
            return;
        }
        if (reachedKnownTerminalBoundary()) {
            boundaryConfirmed = true;
            return;
        }
        final int before = OneWayTripClassifier.direction(route, older);
        final int after = OneWayTripClassifier.direction(route, anchor);
        final boolean forward = anchor.stopOrder() >= older.stopOrder();
        if (changesDirectionAtUnconfirmedTerminal(older, before, after, forward)) {
            candidate = anchor;
            anchor = older;
            return;
        }
        if (!continuesTowardAnchor(before, after, forward)) {
            boundaryConfirmed = true;
            return;
        }
        anchor = older;
        if (OneWayTripClassifier.isDeparture(route, older)) {
            candidate = older;
        }
    }

    private boolean isDisconnectedFromAnchor(Observation older) {
        return !Objects.equals(older.vehicleId(), anchor.vehicleId())
            || older.at().isAfter(anchor.at())
            || Duration.between(older.at(), anchor.at()).compareTo(route.maximumGap()) > 0;
    }

    private boolean isRepeatedTerminalObservation(Observation older) {
        return isTerminal(older) && older.stopOrder() == anchor.stopOrder();
    }

    private boolean reachedKnownTerminalBoundary() {
        return isTerminal(anchor) && candidate != null;
    }

    private boolean changesDirectionAtUnconfirmedTerminal(
        Observation older,
        final int before,
        final int after,
        final boolean forward
    ) {
        final boolean directionChanged = before != after && (forward || (before == 1 && after == 0));
        return directionChanged && isTerminal(older) && !OneWayTripClassifier.isDeparture(route, older);
    }

    private boolean continuesTowardAnchor(final int before, final int after, final boolean forward) {
        final boolean returnsToFirstStop = before == 1
            && anchor.stopOrder() == route.firstStop()
            && !OneWayTripClassifier.isDeparture(route, anchor);
        return before == after && (forward || returnsToFirstStop);
    }

    Observation anchor() {
        return anchor;
    }

    Observation candidate() {
        return candidate;
    }

    Observation replayStart() {
        if (candidate == null) {
            return anchor;
        }
        return candidate;
    }

    boolean boundaryConfirmed() {
        return boundaryConfirmed;
    }

    private boolean isTerminal(Observation observation) {
        return observation.stopOrder() == route.firstStop()
            || Objects.equals(observation.stopOrder(), route.turnStop());
    }

    private static Observation initialCandidate(Route route, Observation anchor, Observation candidate) {
        if (candidate != null) {
            return candidate;
        }
        if (OneWayTripClassifier.isDeparture(route, anchor)) {
            return anchor;
        }
        return null;
    }
}

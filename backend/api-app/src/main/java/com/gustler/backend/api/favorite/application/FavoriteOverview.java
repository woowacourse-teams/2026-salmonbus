package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 회원 소유 정보와 현재 판정보드의 한 정류장을 조합한 즐겨찾기 조회 결과다. */
public record FavoriteOverview(List<Group> groups) {
    public FavoriteOverview {
        groups = List.copyOf(groups);
    }

    public record Group(UUID id, String name, List<Item> favorites) {
        public Group {
            favorites = List.copyOf(favorites);
        }
    }

    public record Item(UUID id, FavoriteTarget target, ItemStatus status, ItemReason reason, BoardSlice board) {
    }

    public record BoardSlice(BoardRoute route, Stop stop) {
    }

    public record Stop(String stopId, String name, BoardDirection direction, List<Vehicle> approachingVehicles) {
        public Stop {
            approachingVehicles = List.copyOf(approachingVehicles);
        }
    }

    /** watch의 접근 차량을 그대로 두고 즐겨찾기 화면용 ETA만 덧붙인다. */
    public record Vehicle(ApproachingVehicle watch, Eta eta) {
    }

    public record Eta(EtaStatus status, EtaReason reason, Instant expectedArrivalAt, Long remainingSeconds) {
        public static Eta available(Instant expectedArrivalAt, long remainingSeconds) {
            return new Eta(EtaStatus.AVAILABLE, null, expectedArrivalAt, remainingSeconds);
        }

        public static Eta unavailable(EtaReason reason) {
            return new Eta(EtaStatus.UNAVAILABLE, reason, null, null);
        }
    }

    public enum ItemStatus {
        READY, NO_VEHICLE, PREPARING, UNAVAILABLE, AMBIGUOUS, ERROR
    }

    public enum ItemReason {
        TARGET_NOT_IN_CURRENT_ROUTE,
        MULTIPLE_CURRENT_VISITS,
        MODEL_NOT_READY,
        OBSERVATION_UNAVAILABLE,
        BOARD_UNAVAILABLE
    }

    public enum EtaStatus {
        AVAILABLE, UNAVAILABLE
    }

    public enum EtaReason {
        SOURCE_NOT_PROVIDED,
        ARRIVAL_UNAVAILABLE,
        VEHICLE_NOT_MATCHED,
        PREDICTION_ELAPSED
    }
}

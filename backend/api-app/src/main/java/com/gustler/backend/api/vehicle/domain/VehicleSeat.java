package com.gustler.backend.api.vehicle.domain;

/**
 * 조회 차량의 알려진 좌석 수와 좌석 미제공 사유를 구분하는 값이다.
 */
public sealed interface VehicleSeat permits VehicleSeat.Exact, VehicleSeat.Unknown {
    static VehicleSeat from(Integer remainingSeats) {
        return from(remainingSeats, null);
    }

    static VehicleSeat from(Integer remainingSeats, String missingReason) {
        // 정규화된 -1은 좌석 수가 null이고 별도 미제공 사유에 저장된다.
        if (remainingSeats == null) {
            if (VehicleSeat.Unknown.REPORTED_UNKNOWN.name().equals(missingReason)) {
                return VehicleSeat.Unknown.REPORTED_UNKNOWN;
            }
            return VehicleSeat.Unknown.NOT_REPORTED;
        }
        if (remainingSeats < 0) {
            return VehicleSeat.Unknown.REPORTED_UNKNOWN;
        }
        return new VehicleSeat.Exact(remainingSeats);
    }

    record Exact(int remaining) implements VehicleSeat {
        public Exact {
            if (remaining < 0) {
                throw new IllegalArgumentException("remaining은 0 이상이어야 합니다.");
            }
        }
    }

    enum Unknown implements VehicleSeat {
        NOT_REPORTED, REPORTED_UNKNOWN,;
    }
}

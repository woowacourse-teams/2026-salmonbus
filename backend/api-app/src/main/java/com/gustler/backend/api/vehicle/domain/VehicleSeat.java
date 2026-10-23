package com.gustler.backend.api.vehicle.domain;

import com.gustler.backend.api.vehicle.domain.VehicleSeat.Exact;
import com.gustler.backend.api.vehicle.domain.VehicleSeat.Unknown;

/**
 * 조회 차량의 알려진 좌석 수와 좌석 미제공 사유를 구분하는 값이다.
 */
public sealed interface VehicleSeat permits Exact, Unknown {
    static VehicleSeat from(Integer remainingSeats) {
        return from(remainingSeats, null, true);
    }

    static VehicleSeat from(Integer remainingSeats, String missingReason, final boolean forecastEligible) {
        // 정규화된 -1은 좌석 수가 null이고 별도 미제공 사유에 저장된다.
        // 원래 미제공된 값은 품질 조사와 겹쳐도 원래 사유를 유지한다.
        if (remainingSeats == null) {
            if (Unknown.REPORTED_UNKNOWN.name().equals(missingReason)) {
                return Unknown.REPORTED_UNKNOWN;
            }
            return Unknown.NOT_REPORTED;
        }
        if (remainingSeats < 0) {
            return Unknown.REPORTED_UNKNOWN;
        }
        if (!forecastEligible) {
            return Unknown.QUALITY_WITHHELD;
        }
        return new Exact(remainingSeats);
    }

    record Exact(int remaining) implements VehicleSeat {
        public Exact {
            if (remaining < 0) {
                throw new IllegalArgumentException("remaining은 0 이상이어야 합니다.");
            }
        }
    }

    enum Unknown implements VehicleSeat {
        NOT_REPORTED,
        REPORTED_UNKNOWN,
        QUALITY_WITHHELD,
        ;
    }
}

package com.gustler.backend.stations.domain;

import com.gustler.backend.station.contract.StationTileId;
import java.util.Optional;

/**
 * 주변정류소 조회로 받은 정류장 한 곳. 미정차 지점(277로 시작)은 만들지 않는다.
 * 정류소번호가 비었거나 00000이면 없는 것으로 둔다.
 */
public record BusStation(
    String stationId,
    String name,
    double x,
    double y,
    String mobileNo,
    String regionName,
    Boolean centerLane
) {

    private static final String NON_BOARDING_PREFIX = "277";

    public BusStation {
        if (hasInvalidStationId(stationId)) {
            throw new IllegalArgumentException("승차 정류장 ID는 277로 시작하지 않는 9자리 숫자여야 한다");
        }
        if (hasInvalidName(name) || hasInvalidCoordinates(x, y)) {
            throw new IllegalArgumentException("정류장 이름과 좌표가 필요하다");
        }
    }

    /**
     * 원천 행의 값을 정리한다. 미정차 지점이거나 필수 값이 없으면 빈 값이다.
     */
    public static Optional<BusStation> from(
        String stationId,
        String name,
        Double x,
        Double y,
        String mobileNo,
        String regionName,
        String centerYn
    ) {
        if (hasInvalidStationId(stationId)) {
            return Optional.empty();
        }
        if (hasInvalidInput(name, x, y)) {
            return Optional.empty();
        }
        return Optional.of(
            new BusStation(
                stationId,
                name.strip(),
                x,
                y,
                mobileNoOf(mobileNo),
                blankToNull(regionName),
                centerLaneOf(centerYn)
            )
        );
    }

    public StationTileId tile() {
        return StationTileId.of(x, y);
    }

    /**
     * 서울·인천으로 등록된 정류장을 구분하는 기존 지역 분류다. API 노출 정책으로 사용하지 않는다.
     */
    public boolean outsideGyeonggi() {
        return "서울".equals(regionName) || "인천".equals(regionName);
    }

    private static boolean hasInvalidStationId(String stationId) {
        if (stationId == null) {
            return true;
        }
        return !stationId.matches("[0-9]{9}") || stationId.startsWith(NON_BOARDING_PREFIX);
    }

    private static boolean hasInvalidName(String name) {
        return name == null || name.isBlank();
    }

    private static boolean hasInvalidCoordinates(final double x, final double y) {
        return !Double.isFinite(x) || !Double.isFinite(y);
    }

    private static boolean hasInvalidInput(String name, Double x, Double y) {
        if (hasInvalidName(name)) {
            return true;
        }
        if (x == null || y == null) {
            return true;
        }
        return hasInvalidCoordinates(x, y);
    }

    private static String mobileNoOf(String value) {
        String stripped = blankToNull(value);
        if (stripped == null || stripped.chars().allMatch(ch -> ch == '0')) {
            return null;
        }
        return stripped;
    }

    private static Boolean centerLaneOf(String value) {
        if ("Y".equals(value)) {
            return true;
        }
        if ("N".equals(value)) {
            return false;
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}

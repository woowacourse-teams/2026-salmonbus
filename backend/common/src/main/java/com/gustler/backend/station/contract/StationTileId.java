package com.gustler.backend.station.contract;

/**
 * 웹 메르카토르 줌 14 칸 번호. 정류장 지도의 조회·채우기 단위다.
 *
 * <p>정류장은 좌표를 내림한 칸 하나에만 속한다. 경도는 서쪽 경계 포함·동쪽 제외, 위도는 북쪽 경계 포함·남쪽 제외다.
 */
public record StationTileId(int x, int y) {

    public static final int ZOOM = 14;
    private static final int TILES = 1 << ZOOM;

    /**
     * 현재 앱의 수집·칸 조회에 정한 경계 상자. 원천 GBIS의 전체 제공 범위와는 별개다.
     */
    private static final double WEST = 126.3;
    private static final double EAST = 127.95;
    private static final double SOUTH = 36.85;
    private static final double NORTH = 38.35;

    public StationTileId {
        if (hasInvalidIndex(x) || hasInvalidIndex(y)) {
            throw new IllegalArgumentException("줌 14 칸 번호는 0 이상 16383 이하여야 한다");
        }
    }

    public static StationTileId of(final double longitude, final double latitude) {
        final double radians = Math.toRadians(latitude);
        final int x = (int) Math.floor((longitude + 180) / 360 * TILES);
        final int y = (int) Math.floor((1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * TILES);
        return new StationTileId(x, y);
    }

    public double west() {
        return (double) x / TILES * 360 - 180;
    }

    public double east() {
        return (double) (x + 1) / TILES * 360 - 180;
    }

    public double north() {
        return latitudeOf(y);
    }

    public double south() {
        return latitudeOf(y + 1);
    }

    /**
     * 칸이 경계 상자와 겹치면 정류장 지도의 범위 안이다.
     */
    public boolean inServiceArea() {
        return east() > WEST && west() < EAST && north() > SOUTH && south() < NORTH;
    }

    public boolean contains(final double longitude, final double latitude) {
        return of(longitude, latitude).equals(this);
    }

    private static boolean hasInvalidIndex(final int index) {
        return index < 0 || index >= TILES;
    }

    private static double latitudeOf(final int row) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * row / TILES))));
    }
}

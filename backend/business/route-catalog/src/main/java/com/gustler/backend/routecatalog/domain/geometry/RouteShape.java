package com.gustler.backend.routecatalog.domain.geometry;

import com.gustler.backend.routecatalog.domain.geometry.RouteShape.Point;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 원본 순서의 X/Y 형상. WGS84라는 공급자 보증이나 실제 차량 위치를 뜻하지 않는다. */
public record RouteShape(List<Point> points) {
    public RouteShape {
        points = List.copyOf(points);
        if (hasInvalidPointCount(points) || hasFewerThanTwoDistinctPoints(points)) {
            throw new IllegalArgumentException("서로 다른 형상점이 두 개 이상 필요하다");
        }
    }

    private static boolean hasInvalidPointCount(List<Point> points) {
        return points.size() < 2 || points.size() > 100_000;
    }

    private static boolean hasFewerThanTwoDistinctPoints(List<Point> points) {
        return points.stream().distinct().limit(2).count() < 2;
    }

    public record Point(double x, double y) {
        public Point {
            if (!isDisplayable(x, y)) {
                throw new IllegalArgumentException("경도·위도로 표시 가능한 범위의 X/Y가 필요하다");
            }
        }

        private static boolean isDisplayable(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return false;
            }
            if (x == 0 || y == 0) {
                return false;
            }
            return x >= -180 && x <= 180 && y >= -90 && y <= 90;
        }
    }

    /**
     * 형상의 좌표 순서와 값으로 내용 지문을 계산한다. 표시명 등 형상 밖의 값은 지문 입력이 아니다.
     */
    public String digest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("GBIS_ROUTE_LINE_V2/ASSUMED_WGS84/v1\n".getBytes(StandardCharsets.UTF_8));
            for (Point point : points) {
                digest.update((point.x() + "," + point.y() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

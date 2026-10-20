package com.gustler.backend.routecatalog.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisRouteLineSource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape.Point;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GBIS 도로 형상점을 카탈로그의 형상 입력으로 변환한다.
 * 원천을 읽지 못한 상태를 정류장 직선으로 대체하지 않는다.
 */
@Component
public class GbisRouteGeometrySource implements RouteGeometrySource {
    private final GbisRouteLineSource lines;
    private final Clock clock;

    public GbisRouteGeometrySource(GbisRouteLineSource lines, Clock clock) {
        this.lines = lines;
        this.clock = clock;
    }

    @Override
    public Optional<Read> read(String sourceRouteId) {
        try {
            return lines.read(sourceRouteId).map(points -> new Read(new RouteShape(points.stream()
                .map(point -> new Point(point.x(), point.y()))
                .toList()), OffsetDateTime.now(clock)));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }
}

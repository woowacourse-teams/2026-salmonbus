package com.gustler.backend.routegeometry.engine.api;

import java.util.List;

/** A generation attempt uses the same direction boundary through alignment, simplification and curve checks. */
interface RouteShapeInput {
    RouteGeometry.Shape shape();
    List<RouteGeometry.Stop> busStops();
    Integer turnSequence();
    List<RouteGeometry.Point> points();
    RoutePaths paths();

    default RoutePathSplitter.Split directionBoundary() {
        return RoutePathSplitter.splitWithBoundary(this);
    }

    default boolean hasVerifiedTurn() { return false; }

    record Prepared(RouteGeometry source, RoutePathSplitter.Split directionBoundary, boolean hasVerifiedTurn) implements RouteShapeInput {
        public RouteGeometry.Shape shape() { return source.shape(); }
        public List<RouteGeometry.Stop> busStops() { return source.busStops(); }
        public Integer turnSequence() { return source.turnSequence(); }
        public List<RouteGeometry.Point> points() { return source.points(); }
        public RoutePaths paths() { return directionBoundary == null ? null : directionBoundary.paths(); }
    }
}

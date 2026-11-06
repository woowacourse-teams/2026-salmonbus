package com.gustler.backend.routegeometry.api;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

public final class RouteDisplayDbSamples {
    private RouteDisplayDbSamples() {}

    public static long seed(JdbcClient jdbc, String id) {
        long route =
                jdbc.sql(
                                "INSERT INTO"
                                    + " route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)"
                                    + " VALUES (?,'GBIS',?,'3330','A','B') RETURNING id")
                        .params(id, id)
                        .query(Long.class)
                        .single();
        long version =
                jdbc.sql(
                                "INSERT INTO"
                                    + " route_version(route_id,turn_sequence,content_digest,valid_from)"
                                    + " VALUES (?,2,?,'2026-10-14T00:00:00Z') RETURNING id")
                        .params(route, "a".repeat(64))
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "INSERT INTO"
                            + " route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)"
                            + " VALUES"
                            + " (?,1,'206000637','A','UP',true),(?,2,'206000638','B','UP',true),(?,3,'206000637','A','DOWN',true)")
                .params(version, version, version)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                            + " route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)"
                            + " VALUES"
                            + " (?,1,'206000637','2026-10-14T00:00:00Z',127,37),(?,2,'206000638','2026-10-14T00:00:00Z',127.01,37.01),(?,3,'206000637','2026-10-14T00:00:00Z',127,37)")
                .params(version, version, version)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                            + " route_geometry(id,route_version_id,received_at,content_digest,points)"
                            + " VALUES"
                            + " (?,?,'2026-10-14T00:00:00Z',?,'[{\"x\":127,\"y\":37},{\"x\":127.01,\"y\":37.01},{\"x\":127,\"y\":37}]')")
                .params(UUID.randomUUID(), version, "b".repeat(64))
                .update();
        return version;
    }
}

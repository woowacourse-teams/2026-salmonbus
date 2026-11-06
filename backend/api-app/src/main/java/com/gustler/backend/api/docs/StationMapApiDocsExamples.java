package com.gustler.backend.api.docs;

/**
 * 공개 명세용 합성 예시. 명부 기준일과 실조회 성공 시각은 서로 독립적이다.
 */
public final class StationMapApiDocsExamples {

    private StationMapApiDocsExamples() {
    }

    public static final String BASELINE = """
        {"tile":{"zoom":14,"x":13976,"y":6354},"status":"READY","source":"GBIS","coordinateSystem":"WGS84",
         "stations":[{"stationId":"206000532","name":"예시 정류장","point":{"x":127.1118,"y":37.3918},
          "mobileNo":"07489","regionName":"성남","direction":null,"salmonRouteIds":[]}],
         "baselineDate":"2026-10-20","verifiedAt":null}
        """;

    public static final String PENDING = """
        {"tile":{"zoom":14,"x":13976,"y":6354},"status":"PENDING","source":"GBIS","coordinateSystem":"WGS84",
         "stations":[],"baselineDate":null,"verifiedAt":null}
        """;

    public static final String DETAIL = """
        {"stationId":"206000532","name":"예시 정류장","point":{"x":127.1118,"y":37.3918},
         "coordinateSystem":"WGS84","regionName":"성남","direction":null,"notice":null,
         "generatedAt":"2026-11-06T07:00:00Z","status":"UNAVAILABLE","reason":"NOT_COLLECTED","snapshot":null,
         "refresh":{"status":"RUNNING","reason":"COLLECTING","suggestedRefreshAt":"2026-11-06T07:00:01Z"},"routes":[]}
        """;

    public static final String INVALID_TILE = """
        {"code":"INVALID_STATION_TILE","message":"정류장 지도 칸 번호가 올바르지 않습니다.","requestId":"example-request"}
        """;
}

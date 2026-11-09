package com.gustler.backend.api.boarding.controller;

/**
 * 탑승 후보 API 문서의 합성 예시다.
 */
final class BoardingOptionsApiDocsExamples {
    static final String WALK = """
        {
          "routeId": "204000057",
          "referenceVersionId": "7",
          "origin": {"stationId": "205000231", "stopSequence": 5, "name": "동강프라자앞", "direction": "UP",
            "boardingAllowed": true},
          "options": [
            {
              "stop": {"stationId": "205000226", "stopSequence": 81, "name": "동강프라자", "direction": "DOWN",
                "boardingAllowed": true},
              "sequenceOffset": 76,
              "relation": "OPPOSITE",
              "accesses": [
                {"durationSeconds": 98,
                  "legs": [{"mode": "WALK", "durationSeconds": 98, "distanceMeters": 103, "source": "PEDESTRIAN_GRAPH"}]}
              ]
            }
          ]
        }
        """;
    static final String UNSUPPORTED_MODE = """
        {"code":"UNSUPPORTED_MODE","message":"지원하지 않는 이동 수단입니다.","requestId":"example-request"}
        """;
    static final String STOP_VISIT_AMBIGUOUS = """
        {"code":"STOP_VISIT_AMBIGUOUS","message":"같은 정류장을 여러 번 지나는 노선이라 stopSequence가 필요합니다.",\
        "requestId":"example-request"}
        """;

    private BoardingOptionsApiDocsExamples() {
    }
}

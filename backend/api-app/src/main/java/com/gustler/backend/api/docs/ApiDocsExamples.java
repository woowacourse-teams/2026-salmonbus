package com.gustler.backend.api.docs;

/** 공개 문서에만 쓰는 합성 요청·응답 예시다. */
public final class ApiDocsExamples {

    public static final String ROUTES = """
        {
          "routes": [
            {
              "id": "123456789",
              "displayName": "예시 노선",
              "startStopName": "기점",
              "endStopName": "종점",
              "status": "PREPARING"
            }
          ]
        }
        """;

    public static final String BOARD_READY = """
        {
          "route": {
            "id": "123456789",
            "displayName": "예시 노선",
            "startStopName": "기점",
            "endStopName": "종점",
            "status": "FORECAST_READY",
            "turnSequence": 2,
            "directions": [
              {
                "id": "UP",
                "name": "회차점 방면",
                "originStopName": "기점",
                "terminalStopName": "회차점",
                "firstDepartureTime": "05:00",
                "lastDepartureTime": "23:00"
              },
              {
                "id": "DOWN",
                "name": "종점 방면",
                "originStopName": "회차점",
                "terminalStopName": "종점",
                "firstDepartureTime": "05:00",
                "lastDepartureTime": "23:00"
              }
            ],
            "referenceVersionId": "example-version"
          },
          "observedAt": "2026-10-01T12:00:00+09:00",
          "staleAt": "2026-10-01T12:05:00+09:00",
          "model": {
            "releaseId": "example-model",
            "trainedThrough": "2026-09-30T00:00:00+09:00"
          },
          "vehiclesInService": 1,
          "stops": [
            {
              "sequence": 1,
              "stopId": "example-stop-1",
              "name": "기점",
              "direction": "UP",
              "boardingAllowed": true,
              "approachingVehicles": []
            },
            {
              "sequence": 2,
              "stopId": "example-stop-2",
              "name": "회차점",
              "direction": "UP",
              "boardingAllowed": false,
              "approachingVehicles": []
            },
            {
              "sequence": 3,
              "stopId": "example-stop-3",
              "name": "종점",
              "direction": "DOWN",
              "boardingAllowed": true,
              "approachingVehicles": [
                {
                  "vehicleId": "example-vehicle",
                  "horizonStops": 2,
                  "forecast": {
                    "status": "AVAILABLE",
                    "seatAvailableProbability": 0.7,
                    "expectedSeats": 4.0
                  }
                }
              ]
            }
          ]
        }
        """;

    public static final String BOARD_PREPARING = """
        {
          "route": {
            "id": "123456789",
            "displayName": "예시 노선",
            "startStopName": "기점",
            "endStopName": "종점",
            "status": "PREPARING",
            "turnSequence": 2,
            "directions": [
              {
                "id": "UP",
                "name": "회차점 방면",
                "originStopName": "기점",
                "terminalStopName": "회차점",
                "firstDepartureTime": "05:00",
                "lastDepartureTime": "23:00"
              },
              {
                "id": "DOWN",
                "name": "종점 방면",
                "originStopName": "회차점",
                "terminalStopName": "종점",
                "firstDepartureTime": "05:00",
                "lastDepartureTime": "23:00"
              }
            ],
            "referenceVersionId": "example-version"
          },
          "observedAt": "2026-10-01T12:00:00+09:00",
          "staleAt": "2026-10-01T12:05:00+09:00",
          "model": {
            "releaseId": "example-model",
            "trainedThrough": "2026-09-30T00:00:00+09:00"
          },
          "vehiclesInService": 1,
          "stops": [
            {
              "sequence": 1,
              "stopId": "example-stop-1",
              "name": "기점",
              "direction": "UP",
              "boardingAllowed": true,
              "approachingVehicles": []
            },
            {
              "sequence": 2,
              "stopId": "example-stop-2",
              "name": "회차점",
              "direction": "UP",
              "boardingAllowed": false,
              "approachingVehicles": []
            },
            {
              "sequence": 3,
              "stopId": "example-stop-3",
              "name": "종점",
              "direction": "DOWN",
              "boardingAllowed": true,
              "approachingVehicles": [
                {
                  "vehicleId": "example-vehicle",
                  "horizonStops": 2,
                  "forecast": {
                    "status": "UNAVAILABLE"
                  }
                }
              ]
            }
          ]
        }
        """;

    public static final String VEHICLES_PRESENT = """
        {
          "routeId": "123456789",
          "referenceVersionId": "example-version",
          "observation": {
            "state": "VEHICLES_PRESENT",
            "observedAt": "2026-10-01T12:00:00+09:00",
            "staleAt": "2026-10-01T12:05:00+09:00"
          },
          "vehicles": [
            {
              "vehicleId": "example-vehicle",
              "direction": "UP",
              "currentStopSequence": 1,
              "stopId": "example-stop",
              "stopName": "기점",
              "phase": "IN_TRANSIT",
              "seat": {
                "kind": "EXACT",
                "remaining": 4
              }
            },
            {
              "vehicleId": "example-vehicle-2",
              "direction": "DOWN",
              "currentStopSequence": 2,
              "stopId": "example-stop-2",
              "stopName": "종점",
              "phase": "ARRIVING",
              "seat": {
                "kind": "UNKNOWN",
                "reason": "QUALITY_WITHHELD"
              }
            }
          ]
        }
        """;

    public static final String VEHICLES_UNKNOWN = """
        {
          "routeId": "123456789",
          "referenceVersionId": "example-version",
          "observation": {
            "state": "UNKNOWN",
            "observedAt": null,
            "staleAt": null
          },
          "vehicles": []
        }
        """;

    public static final String CHAT_ROOM = """
        {
          "routeId": "123456789",
          "displayName": "예시 노선",
          "historySize": 50,
          "maxBodyCodePoints": 200
        }
        """;

    public static final String INVALID_ROUTE = """
        {
          "code": "INVALID_ROUTE_ID",
          "message": "routeId는 9자리 숫자여야 합니다.",
          "requestId": "example-request"
        }
        """;

    public static final String ROUTE_NOT_FOUND = """
        {
          "code": "ROUTE_NOT_FOUND",
          "message": "등록되지 않은 노선입니다.",
          "requestId": "example-request"
        }
        """;

    public static final String MODEL_OUT_OF_SCOPE = """
        {
          "code": "MODEL_OUT_OF_SCOPE",
          "message": "활성 모델 번들이 지원하지 않는 노선 판본입니다.",
          "requestId": "example-request"
        }
        """;

    public static final String NO_RECENT_OBSERVATION = """
        {
          "code": "NO_RECENT_OBSERVATION",
          "message": "예보 기준으로 사용할 최근 차량 관측이 없습니다.",
          "requestId": "example-request"
        }
        """;

    public static final String SERVICE_UNAVAILABLE = """
        {
          "code": "SERVICE_UNAVAILABLE",
          "message": "일시적인 서버 장애가 발생했습니다.",
          "requestId": "example-request"
        }
        """;

    public static final String METHOD_NOT_ALLOWED = """
        {
          "code": "METHOD_NOT_ALLOWED",
          "message": "이 경로에서 지원하지 않는 요청 방식입니다.",
          "requestId": "example-request"
        }
        """;

    public static final String INTERNAL_ERROR = """
        {
          "code": "INTERNAL_ERROR",
          "message": "요청을 처리하지 못했습니다.",
          "requestId": "example-request"
        }
        """;

    private ApiDocsExamples() {
    }
}

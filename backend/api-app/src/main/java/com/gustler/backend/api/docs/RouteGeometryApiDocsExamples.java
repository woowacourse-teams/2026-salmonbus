package com.gustler.backend.api.docs;

/** 공개 문서용 합성 예시. contract의 같은 이름 예시와 문서 테스트에서 대조한다. */
public final class RouteGeometryApiDocsExamples {

    public static final String READY = """
        {
          "routeId": "204000057",
          "referenceVersionId": "7",
          "geometryRevision": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
          "name": "3330",
          "startPoint": "합성 기점 A",
          "endPoint": "합성 회차 B",
          "status": "READY",
          "reason": null,
          "coordinateSystem": "WGS84_ASSUMED",
          "turningPoint": {
            "stopSequence": 2
          },
          "busStops": [
            {
              "stationId": "206000637",
              "stopSequence": 1,
              "name": "합성 A",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            },
            {
              "stationId": "206000638",
              "stopSequence": 2,
              "name": "합성 B",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127.2,
                "y": 37.1
              }
            },
            {
              "stationId": "206000637",
              "stopSequence": 3,
              "name": "합성 A",
              "direction": "DOWN",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            }
          ],
          "points": [
            {
              "x": 127,
              "y": 37
            },
            {
              "x": 127.1,
              "y": 37.05
            },
            {
              "x": 127.2,
              "y": 37.1
            },
            {
              "x": 127,
              "y": 37
            }
          ],
          "paths": {
            "UP": {
              "points": [
                {
                  "x": 127,
                  "y": 37
                },
                {
                  "x": 127.1,
                  "y": 37.05
                },
                {
                  "x": 127.2,
                  "y": 37.1
                }
              ]
            },
            "DOWN": {
              "points": [
                {
                  "x": 127.2,
                  "y": 37.1
                },
                {
                  "x": 127,
                  "y": 37
                }
              ]
            }
          },
          "boundary": {
            "left": 127,
            "top": 37.1,
            "right": 127.2,
            "bottom": 37
          }
        }
        """;

    public static final String PARTIAL = """
        {
          "routeId": "204000057",
          "referenceVersionId": "7",
          "geometryRevision": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
          "name": "3330",
          "startPoint": "합성 기점 A",
          "endPoint": "합성 회차 B",
          "status": "PARTIAL",
          "reason": "MISSING_STOP_COORDINATES",
          "coordinateSystem": "WGS84_ASSUMED",
          "turningPoint": {
            "stopSequence": 2
          },
          "busStops": [
            {
              "stationId": "206000637",
              "stopSequence": 1,
              "name": "합성 A",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            },
            {
              "stationId": "206000638",
              "stopSequence": 2,
              "name": "합성 B",
              "direction": "UP",
              "boardingAllowed": true,
              "point": null
            },
            {
              "stationId": "206000637",
              "stopSequence": 3,
              "name": "합성 A",
              "direction": "DOWN",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            }
          ],
          "points": [
            {
              "x": 127,
              "y": 37
            },
            {
              "x": 127.1,
              "y": 37.05
            },
            {
              "x": 127.2,
              "y": 37.1
            },
            {
              "x": 127,
              "y": 37
            }
          ],
          "paths": null,
          "boundary": {
            "left": 127,
            "top": 37.1,
            "right": 127.2,
            "bottom": 37
          }
        }
        """;

    public static final String NOT_COLLECTED = """
        {
          "routeId": "204000057",
          "referenceVersionId": "7",
          "geometryRevision": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
          "name": "3330",
          "startPoint": "합성 기점 A",
          "endPoint": "합성 회차 B",
          "status": "UNAVAILABLE",
          "reason": "NOT_COLLECTED",
          "coordinateSystem": "WGS84_ASSUMED",
          "turningPoint": {
            "stopSequence": 2
          },
          "busStops": [
            {
              "stationId": "206000637",
              "stopSequence": 1,
              "name": "합성 A",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            },
            {
              "stationId": "206000638",
              "stopSequence": 2,
              "name": "합성 B",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127.2,
                "y": 37.1
              }
            },
            {
              "stationId": "206000637",
              "stopSequence": 3,
              "name": "합성 A",
              "direction": "DOWN",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            }
          ],
          "points": [],
          "paths": null,
          "boundary": {
            "left": 127,
            "top": 37.1,
            "right": 127.2,
            "bottom": 37
          }
        }
        """;

    public static final String WITHOUT_DIRECTION_SPLIT = """
        {
          "routeId": "204000057",
          "referenceVersionId": "7",
          "geometryRevision": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
          "name": "3330",
          "startPoint": "합성 기점 A",
          "endPoint": "합성 회차 B",
          "status": "READY",
          "reason": null,
          "coordinateSystem": "WGS84_ASSUMED",
          "turningPoint": null,
          "busStops": [
            {
              "stationId": "206000637",
              "stopSequence": 1,
              "name": "합성 A",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            },
            {
              "stationId": "206000638",
              "stopSequence": 2,
              "name": "합성 B",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127.2,
                "y": 37.1
              }
            },
            {
              "stationId": "206000637",
              "stopSequence": 3,
              "name": "합성 A",
              "direction": "UP",
              "boardingAllowed": true,
              "point": {
                "x": 127,
                "y": 37
              }
            }
          ],
          "points": [
            {
              "x": 127,
              "y": 37
            },
            {
              "x": 127.1,
              "y": 37.05
            },
            {
              "x": 127.2,
              "y": 37.1
            },
            {
              "x": 127,
              "y": 37
            }
          ],
          "paths": null,
          "boundary": {
            "left": 127,
            "top": 37.1,
            "right": 127.2,
            "bottom": 37
          }
        }
        """;

    private RouteGeometryApiDocsExamples() { }
}

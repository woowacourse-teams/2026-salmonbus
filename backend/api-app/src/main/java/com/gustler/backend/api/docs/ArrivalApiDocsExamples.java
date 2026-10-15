package com.gustler.backend.api.docs;

/** 공개 문서용 합성 예시. contract의 같은 이름 예시와 문서 테스트에서 대조한다. */
public final class ArrivalApiDocsExamples {

    public static final String READY = """
        {
          "stationId": "206000637",
          "generatedAt": "2026-10-14T12:00:10+09:00",
          "status": "READY",
          "reason": null,
          "snapshot": {
            "snapshotId": "00000000-0000-0000-0000-000000000001",
            "receivedAt": "2026-10-14T12:00:00+09:00",
            "sourceObservedAt": null,
            "sourceQueryTime": "2026-10-14 12:00:00",
            "refreshAfterAt": "2026-10-14T12:00:40+09:00",
            "staleAt": "2026-10-14T12:01:00+09:00"
          },
          "refresh": {
            "status": "IDLE",
            "reason": null,
            "suggestedRefreshAt": "2026-10-14T12:00:40+09:00"
          },
          "routes": [
            {
              "routeId": "204000057",
              "name": "3330",
              "targetVisit": {
                "referenceVersionId": "7",
                "stopSequence": 22,
                "sourceStopOrder": 22,
                "matchStatus": "MATCHED",
                "direction": "UP"
              },
              "status": "READY",
              "reason": null,
              "arrivals": [
                {
                  "vehicleId": "example-vehicle-1",
                  "ordinal": 1,
                  "remainingStops": 3,
                  "eta": {
                    "status": "AVAILABLE",
                    "reason": null,
                    "resolution": "SECONDS",
                    "estimateBaseAt": "2026-10-14T12:00:00+09:00",
                    "baseKind": "RECEIVED_AT_APPROXIMATION",
                    "expectedArrivalAt": "2026-10-14T12:03:00+09:00",
                    "remainingSeconds": 170
                  },
                  "currentSeat": {
                    "kind": "EXACT",
                    "remaining": 0,
                    "reason": null
                  },
                  "congestionCode": null,
                  "vehicleTypeCode": 0,
                  "taglessSupported": false
                }
              ]
            }
          ]
        }
        """;

    public static final String COLLECTING = """
        {
          "stationId": "206000637",
          "generatedAt": "2026-10-14T12:00:10+09:00",
          "status": "UNAVAILABLE",
          "reason": "NOT_COLLECTED",
          "snapshot": null,
          "refresh": {
            "status": "RUNNING",
            "reason": "COLLECTING",
            "suggestedRefreshAt": "2026-10-14T12:00:12+09:00"
          },
          "routes": []
        }
        """;

    public static final String NO_PREDICTION = """
        {
          "stationId": "206000637",
          "generatedAt": "2026-10-14T12:00:10+09:00",
          "status": "NO_PREDICTION",
          "reason": null,
          "snapshot": {
            "snapshotId": "00000000-0000-0000-0000-000000000001",
            "receivedAt": "2026-10-14T12:00:00+09:00",
            "sourceObservedAt": null,
            "sourceQueryTime": "2026-10-14 12:00:00",
            "refreshAfterAt": "2026-10-14T12:00:40+09:00",
            "staleAt": "2026-10-14T12:01:00+09:00"
          },
          "refresh": {
            "status": "IDLE",
            "reason": null,
            "suggestedRefreshAt": "2026-10-14T12:00:40+09:00"
          },
          "routes": [
            {
              "routeId": "204000057",
              "name": "3330",
              "targetVisit": {
                "referenceVersionId": "7",
                "stopSequence": 22,
                "sourceStopOrder": 22,
                "matchStatus": "MATCHED",
                "direction": "UP"
              },
              "status": "NO_PREDICTION",
              "reason": null,
              "arrivals": []
            }
          ]
        }
        """;

    public static final String STALE = """
        {
          "stationId": "206000637",
          "generatedAt": "2026-10-14T12:01:01+09:00",
          "status": "UNAVAILABLE",
          "reason": "STALE",
          "snapshot": {
            "snapshotId": "00000000-0000-0000-0000-000000000001",
            "receivedAt": "2026-10-14T12:00:00+09:00",
            "sourceObservedAt": null,
            "sourceQueryTime": "2026-10-14 12:00:00",
            "refreshAfterAt": "2026-10-14T12:00:40+09:00",
            "staleAt": "2026-10-14T12:01:00+09:00"
          },
          "refresh": {
            "status": "RUNNING",
            "reason": "COLLECTING",
            "suggestedRefreshAt": "2026-10-14T12:01:03+09:00"
          },
          "routes": []
        }
        """;

    private ArrivalApiDocsExamples() { }
}

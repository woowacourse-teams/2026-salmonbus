package com.gustler.backend.api.arrival.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.arrival.application.ArrivalFreshnessPolicy;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StationArrivalsContractTest {
    @Test
    void 조회_판단과_DTO를_분리해도_외부_응답은_유지한다() throws Exception {
        // given
        Instant received = Instant.parse("2026-10-14T03:00:00Z");
        ArrivalReadRepository repository = mock(ArrivalReadRepository.class);
        ArrivalRefreshGateway gateway = mock(ArrivalRefreshGateway.class);
        ArrivalSnapshot snapshot = new ArrivalSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "206000637", received, received.plusSeconds(40), received.plusSeconds(60), "source query time",
            List.of(new RouteArrival("204000057", "3330", 22, 12, null, null, "RUN",
                List.of(new Prediction("vehicle-1", 1, 180, 3, 3, 0, null, null, 0, false)))));
        when(repository.visits("206000637")).thenReturn(List.of(
            new ArrivalVisit("204000057", "204000057", 7, 22, "UP", received.minusSeconds(100))));
        when(repository.latest("206000637")).thenReturn(Optional.of(snapshot));
        ArrivalQueryService service = new ArrivalQueryService(repository, gateway, new ArrivalFreshnessPolicy(),
            Clock.fixed(received.plusSeconds(10), ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StationArrivalsController(service)).build();

        // when
        mvc.perform(get("/api/v2/stations/206000637/arrivals")
                .param("routeId", "204000057").param("referenceVersionId", "7").param("stopSequence", "22"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=5, public"))
            .andExpect(content().json("""
                {
                  "stationId":"206000637", "generatedAt":"2026-10-14T03:00:10Z",
                  "status":"READY", "reason":null,
                  "snapshot":{
                    "snapshotId":"00000000-0000-0000-0000-000000000001",
                    "receivedAt":"2026-10-14T03:00:00Z", "sourceObservedAt":null,
                    "sourceQueryTime":"source query time",
                    "refreshAfterAt":"2026-10-14T03:00:40Z", "staleAt":"2026-10-14T03:01:00Z"
                  },
                  "refresh":{"status":"IDLE", "reason":null, "suggestedRefreshAt":"2026-10-14T03:00:40Z"},
                  "routes":[{
                    "routeId":"204000057", "name":"3330", "status":"READY", "reason":null,
                    "targetVisit":{"referenceVersionId":"7", "stopSequence":22, "sourceStopOrder":22,
                                   "matchStatus":"MATCHED", "direction":"UP"},
                    "arrivals":[{
                      "vehicleId":"vehicle-1", "ordinal":1, "remainingStops":3,
                      "eta":{"status":"AVAILABLE", "reason":null, "resolution":"SECONDS",
                             "estimateBaseAt":"2026-10-14T03:00:00Z", "baseKind":"RECEIVED_AT_APPROXIMATION",
                             "expectedArrivalAt":"2026-10-14T03:03:00Z", "remainingSeconds":170},
                      "currentSeat":{"kind":"EXACT", "remaining":0, "reason":null},
                      "congestionCode":null, "vehicleTypeCode":0, "taglessSupported":false
                    }]
                  }]
                }
                """, JsonCompareMode.STRICT));

        // then
        verifyNoInteractions(gateway);
    }
}

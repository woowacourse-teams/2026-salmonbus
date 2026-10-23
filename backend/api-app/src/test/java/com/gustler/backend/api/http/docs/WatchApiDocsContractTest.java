package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map.Entry;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ActiveProfiles("api-docs-local")
class WatchApiDocsContractTest {
    private static final String WATCH = "/api/v1/routes/{routeId}/watch";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private JsonNode generated;
    private JsonNode contract;

    @BeforeEach
    void documents() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/docs/openapi"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        generated = json.readTree(body);
        contract = json.readTree(Files.readString(Path.of(System.getProperty("watch.openapi.contract"))));
    }

    @Test
    void 생성한_문서의_watch_경로가_프론트_공유명세와_같다() {
        // given
        JsonNode source = contract.path("paths").path(WATCH).path("get");
        JsonNode operation = generated.path("paths").path(WATCH).path("get");

        // when
        JsonNode sourceExamples = source.at("/responses/200/content/application~1json/examples");
        JsonNode generatedExamples = operation.at("/responses/200/content/application~1json/examples");

        // then
        assertThat(operation.path("operationId")).isEqualTo(source.path("operationId"));
        assertThat(operation.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(
            source.path("responses").propertyNames());
        assertThat(generatedExamples.size()).isEqualTo(sourceExamples.size());
        for (Entry<String, JsonNode> example : sourceExamples.properties()) {
            assertThat(generatedExamples.path(example.getKey()).path("value")).as(example.getKey())
                .isEqualTo(example.getValue().path("value"));
        }
        assertThat(operation.at("/responses/200/headers/Cache-Control"))
            .isEqualTo(source.at("/responses/200/headers/Cache-Control"));
    }

    @Test
    void 생성한_문서의_응답_스키마가_프론트_공유명세와_같다() {
        // given
        JsonNode sourceSchemas = contract.at("/components/schemas");
        JsonNode generatedSchemas = generated.at("/components/schemas");

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            for (String name : List.of(
                "WatchBoardResponse", "VehicleResponse", "SeatResponse", "ExactSeat", "UnknownSeat",
                "StopStateResponse", "ApproachingVehicleResponse", "ForecastResponse", "BoardRouteResponse",
                "ErrorResponse"
            )) {
                softly.assertThat(generatedSchemas.path(name)).as(name).isEqualTo(sourceSchemas.path(name));
            }
        }
    }
}

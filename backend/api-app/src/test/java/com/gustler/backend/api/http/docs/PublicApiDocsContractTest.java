package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ActiveProfiles("api-docs-local")
class PublicApiDocsContractTest {
    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void 익명_방문자에게_현재_구현된_HTTP_GET만_명세로_보인다() throws Exception {
        // given
        String body = mockMvc.perform(get("/api/docs/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/routes'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/routes/{routeId}/board'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/routes/{routeId}/vehicles'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/routes/{routeId}/watch'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v2/stations/{stationId}/arrivals'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v2/routes/{routeId}/geometry'].get").exists())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // when
        JsonNode document = objectMapper.readTree(body);
        JsonNode paths = document.path("paths");

        // then
        assertThat(paths.size()).isEqualTo(6);
        assertThat(document.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(document.path("components").path("schemas")
            .path("ForecastResponse").path("properties")
            .path("expectedSeats").path("description").asText()).contains("생략");
        assertThat(document.path("components").path("schemas")
            .path("ForecastResponse").path("required").toString())
            .contains("status")
            .doesNotContain("seatAvailableProbability", "expectedSeats");
        JsonNode schemas = document.path("components").path("schemas");
        assertThat(schemas.path("RouteListResponse").path("required").toString()).contains("routes");
        assertThat(schemas.path("BoardResponse").path("required").toString())
            .contains("route", "observedAt", "staleAt", "model", "stops");
        assertThat(schemas.path("WatchBoardResponse").path("required").toString())
            .contains("route", "observedAt", "staleAt", "model", "stops", "vehicles");
        assertThat(schemas.path("WatchBoardResponse").path("properties").path("vehicles").path("items")
            .path("$ref").asText()).isEqualTo("#/components/schemas/VehicleResponse");
        assertThat(paths.path("/api/v1/routes/{routeId}/watch").path("get").path("operationId").asText())
            .isEqualTo("getRouteWatch");
        assertThat(schemas.path("ObservationResponse").path("required").toString())
            .contains("state", "observedAt", "staleAt");
        assertThat(schemas.path("SeatResponse").path("oneOf").size()).isEqualTo(2);
        assertThat(schemas.path("SeatResponse").path("discriminator").path("mapping")
            .path("EXACT").asText()).isEqualTo("#/components/schemas/ExactSeat");
        assertThat(schemas.path("ExactSeat").path("properties").path("kind")
            .path("enum").toString()).isEqualTo("[\"EXACT\"]");
        assertThat(schemas.path("UnknownSeat").path("properties").path("kind")
            .path("enum").toString()).isEqualTo("[\"UNKNOWN\"]");
        JsonNode successContent = paths.path("/api/v1/routes").path("get")
            .path("responses").path("200").path("content");
        assertThat(successContent.toString()).contains("application/json").doesNotContain("*/*");
        for (String route : List.of("/api/v1/routes", "/api/v1/routes/{routeId}/board",
            "/api/v1/routes/{routeId}/vehicles", "/api/v1/routes/{routeId}/watch",
            "/api/v2/stations/{stationId}/arrivals",
        "/api/v2/routes/{routeId}/geometry")) {
            JsonNode responses = paths.path(route).path("get").path("responses");
            for (String statusCode : List.of("200", "503", "405", "500")) {
                JsonNode content = responses.path(statusCode).path("content").path("application/json");
                assertThat(content.path("schema").toString()).as(route + " " + statusCode).isNotEqualTo("");
                assertThat(content.path("example").toString().length()
                    + content.path("examples").size()).as(route + " " + statusCode + " examples")
                    .isPositive();
            }
        }
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(body).doesNotContain("/api/chat/rooms/{routeId}/stream", "/actuator", "/readyz",
            "/internal/arrivals/refresh");
            softly.assertThat(body).doesNotContain("DB_URL", "CHAT_MONGODB_URI", "jdbc:", "clientSecret");
        }
    }

    @Test
    void 익명_방문자가_UI_설정을_보되_시험_요청은_할_수_없다() throws Exception {
        // given
        mockMvc.perform(get("/api/docs"))
            .andExpect(status().isFound())
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/docs/ui"));
        mockMvc.perform(get("/api/docs/ui"))
            .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/docs/openapi/swagger-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.supportedSubmitMethods").isEmpty())
            .andExpect(jsonPath("$.validatorUrl").value("none"));

        mockMvc.perform(get("/api/docs/swagger-ui/index.html"))
            .andExpect(status().isOk());

        String yaml = mockMvc.perform(get("/api/docs/openapi.yaml"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(yaml).contains("openapi:", "/api/v1/routes", "/api/v2/stations/", "/api/v2/routes/");
    }

    @Test
    void 종료한_채팅_HTTP와_연결_문서_경로는_더_이상_제공하지_않는다() throws Exception {
        for (String path : List.of("/api/chat/rooms/234000050",
            "/api/chat/rooms/234000050/stream", "/api/docs/chat", "/api/docs/chat/examples")) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void 문서가_켜져도_기존_공개_조회에는_로그인이_필요하지_않다() throws Exception {
        // when
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk());
    }
}

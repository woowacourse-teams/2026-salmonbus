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
class V2ApiDocsContractTest {
    private static final String ARRIVALS = "/api/v2/stations/{stationId}/arrivals";
    private static final String GEOMETRY = "/api/v2/routes/{routeId}/geometry";
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    private JsonNode document;

    @BeforeEach
    void document() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/docs/openapi"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        document = json.readTree(body);
    }

    @Test
    void 실제_생성한_문서의_예시와_응답코드를_프론트_공유명세와_대조한다() throws Exception {
        // when
        for (String[] entry : List.of(new String[]{ARRIVALS, "arrival.openapi.contract"},
            new String[]{GEOMETRY, "geometry.openapi.contract"})) {
            JsonNode contract = json.readTree(Files.readString(Path.of(System.getProperty(entry[1]))));
            JsonNode source = contract.path("paths").path(entry[0]).path("get");
            JsonNode generated = operation(entry[0]);

            // then
            assertThat(generated.path("operationId")).isEqualTo(source.path("operationId"));
            for (JsonNode parameter : generated.path("parameters")) {
                String name = parameter.path("name").asString();
                if (name.equals("stationId") || name.equals("routeId")
                    || entry[0].equals(GEOMETRY) && name.equals("referenceVersionId")) {
                    assertThat(parameter.at("/schema/type").asString()).isEqualTo("string");
                    assertThat(parameter.path("example").isTextual()).as(entry[0] + " " + name).isTrue();
                }
            }
            JsonNode sourceExamples = source.at("/responses/200/content/application~1json/examples");
            JsonNode generatedExamples = generated.at("/responses/200/content/application~1json/examples");
            assertThat(generatedExamples.size()).isEqualTo(sourceExamples.size());
            for (Entry<String, JsonNode> example : sourceExamples.properties()) {
                assertThat(generatedExamples.path(example.getKey()).path("value"))
                    .as(entry[0] + " " + example.getKey()).isEqualTo(example.getValue().path("value"));
            }
            for (String code : List.of("200", "400", "404", "409", "503", "405", "500")) {
                JsonNode response = generated.path("responses").path(code);
                assertThat(response.at("/content/application~1json/schema")
                    .isMissingNode()).as(entry[0] + " " + code)
                    .isFalse();
            }
        }
        assertThat(operation(GEOMETRY).at("/responses/304/content").isMissingNode()).isTrue();
        assertThat(operation(GEOMETRY).at("/responses/200/headers/ETag").isMissingNode()).isFalse();
        assertThat(operation(ARRIVALS).at("/responses/200/headers/Cache-Control").isMissingNode()).isFalse();
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/v2-generated-openapi.json"), json.writerWithDefaultPrettyPrinter()
            .writeValueAsString(document));
    }

    @Test
    void ETA의_상태와_단위_좌석0_미제공_구분을_문서화한다() {
        // when
        JsonNode schemas = document.at("/components/schemas");

        // then
        assertThat(schemas.at("/StationArrivalsResponse/properties/status/enum").toString())
            .contains("READY", "NO_PREDICTION", "UNAVAILABLE");
        assertThat(schemas.at("/ArrivalRefresh/properties/status/enum").toString())
            .contains("IDLE", "RUNNING", "BLOCKED", "FAILED");
        assertThat(schemas.at("/ArrivalEta/properties/resolution/enum").toString()).contains("SECONDS", "MINUTES");
        assertThat(schemas.at("/ArrivalVehicle/properties/ordinal/maximum").intValue()).isEqualTo(2);
        assertThat(schemas.at("/ArrivalSeat/required").toString()).contains("kind", "remaining", "reason");
        assertThat(schemas.at("/ArrivalSeat/properties/remaining/minimum").intValue()).isZero();
        assertThat(schemas.at("/ArrivalVehicle/properties").has("ratio")).isFalse();
        assertThat(schemas.at("/ArrivalSnapshot/properties/sourceObservedAt/description")
            .asString()).contains("알 수 없어", "null");
        assertThat(schemas.at("/ArrivalTargetVisit/properties/direction/enum")
            .toString()).contains("UP", "DOWN", "null");
    }

    @Test
    void geometry의_양방향과_명시적_null_필드를_필수항목으로_문서화한다() {
        // when
        JsonNode schemas = document.at("/components/schemas");

        // then
        assertThat(schemas.at("/RouteGeometryResponse/required")
            .toString()).contains("paths", "turningPoint", "boundary");
        assertThat(schemas.at("/RouteGeometryPaths/required").toString()).contains("UP", "DOWN");
        assertThat(schemas.at("/RouteGeometryPaths/properties").size()).isEqualTo(2);
        assertThat(schemas.at("/RouteGeometryPaths/additionalProperties").booleanValue()).isFalse();
        assertThat(schemas.at("/RouteGeometryPath/properties/points/minItems").intValue()).isEqualTo(2);
        assertThat(schemas.at("/RouteGeometryPoint/properties/x/description").asString()).contains("경도");
        assertThat(schemas.at("/RouteGeometryPoint/properties/y/description").asString()).contains("위도");
        assertThat(schemas.at("/RouteGeometryStop/required").toString()).contains("point", "stopSequence");
        for (String[] field : List.of(new String[]{"RouteGeometryResponse", "paths", "RouteGeometryPaths"},
            new String[]{"RouteGeometryResponse", "turningPoint", "RouteTurningPoint"},
            new String[]{"RouteGeometryResponse", "boundary", "RouteGeometryBoundary"},
            new String[]{"RouteGeometryStop", "point", "RouteGeometryPoint"},
            new String[]{"StationArrivalsResponse", "snapshot", "ArrivalSnapshot"})) {
            JsonNode property = schemas.path(field[0]).path("properties").path(field[1]);
            assertThat(property.has("$ref")).as(field[1]).isFalse();
            assertThat(property.has("type")).as(field[1]).isFalse();
            assertThat(property.path("anyOf")
                .get(0)
                .path("$ref")
                .asString()).isEqualTo("#/components/schemas/" + field[2]);
            assertThat(property.path("anyOf").get(1).path("type").asString()).isEqualTo("null");
            assertThat(schemas.path(field[2]).path("type").asString()).isEqualTo("object");
        }
        assertThat(schemas.at("/ArrivalEta/properties/remainingSeconds/type").toString()).contains("integer", "null");
    }

    private JsonNode operation(String path) {
        return document.path("paths").path(path).path("get");
    }
}

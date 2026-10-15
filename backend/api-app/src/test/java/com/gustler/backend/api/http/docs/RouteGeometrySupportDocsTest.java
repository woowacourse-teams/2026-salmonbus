package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
class RouteGeometrySupportDocsTest {
    private static final String GEOMETRY = "/api/v2/routes/{routeId}/geometry";

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    private JsonNode document;
    private JsonNode contract;

    @BeforeEach
    void documents() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/docs/openapi"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        document = json.readTree(body);
        contract = json.readTree(Files.readString(Path.of(System.getProperty("geometry.openapi.contract"))));
    }

    @Test
    void 생성문서와_공유명세가_dev_정적자료_범위와_수집대기_상태를_함께_설명한다() {
        var operation = operation(document);
        var shared = operation(contract);

        assertThat(operation.path("operationId").asString()).isEqualTo("getRouteGeometry");
        assertThat(operation.path("summary")).isEqualTo(shared.path("summary"));
        assertThat(operation.path("description")).isEqualTo(shared.path("description"));
        assertThat(operation.path("description").asString())
            .contains("41노선", "3,215건", "35,542개", "정적 재생 자료", "초기 수집", "실시간 최신성");
        for (String code : List.of("200", "409", "503")) {
            assertThat(operation.path("responses").path(code).path("description"))
                .as("HTTP " + code).isEqualTo(shared.path("responses").path(code).path("description"));
        }
        assertThat(operation.at("/responses/200/description").asString())
            .contains("READY", "PARTIAL", "UNAVAILABLE", "paths=null");
        assertThat(operation.at("/responses/409/description").asString()).contains("referenceVersionId 없이");
        assertThat(operation.at("/responses/503/description").asString()).contains("200/UNAVAILABLE", "no-store");
        assertThat(operation.at("/responses/200/headers/Cache-Control/description").asString())
            .contains("READY/PARTIAL", "max-age=300", "UNAVAILABLE", "no-store");
    }

    @Test
    void 마커와_형상을_구분하고_READY여도_방향분리가_없을_수_있음을_필드와_예시에_명시한다() {
        var properties = document.at("/components/schemas/RouteGeometryResponse/properties");
        assertThat(properties.at("/points/description").asString()).contains("별도 자료", "정류장 직선", "빈 배열");
        assertThat(properties.at("/busStops/description").asString()).contains("마커", "stopSequence");
        assertThat(properties.at("/paths/description").asString()).contains("함께 제공", "모호", "READY", "null");
        assertThat(properties.at("/turningPoint/description").asString()).contains("방문 순번", "인덱스");
        assertThat(properties.at("/status/description").asString()).contains("READY여도 paths=null");

        var examples = operation(document).at("/responses/200/content/application~1json/examples");
        var shared = operation(contract).at("/responses/200/content/application~1json/examples");
        for (String name : List.of("ready", "partial", "notCollected", "withoutDirectionSplit")) {
            var example = examples.path(name);
            assertThat(example.path("summary").asString()).as(name).isNotBlank();
            assertThat(example.path("description").asString()).as(name).contains("합성 예시");
            assertThat(example.path("summary")).as(name).isEqualTo(shared.path(name).path("summary"));
            assertThat(example.path("description")).as(name).isEqualTo(shared.path(name).path("description"));
        }
        assertThat(examples.at("/withoutDirectionSplit/value/status").asString()).isEqualTo("READY");
        assertThat(examples.at("/withoutDirectionSplit/value/paths").isNull()).isTrue();
        assertThat(examples.at("/partial/value/reason").asString()).isEqualTo("MISSING_STOP_COORDINATES");
        assertThat(examples.at("/notCollected/value/points")).isEmpty();
    }

    private JsonNode operation(JsonNode source) {
        return source.path("paths").path(GEOMETRY).path("get");
    }
}

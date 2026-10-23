package com.gustler.backend.api.favorite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ActiveProfiles("api-docs-local")
@TestPropertySource(properties = "auth.enabled=true")
class FavoriteApiDocsContractTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;

    private JsonNode document;

    @BeforeEach
    void 문서를_생성한다() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(context).build()
            .perform(get("/api/docs/openapi"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        document = json.readTree(body);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/favorite-generated-openapi.json"),
            json.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }

    @Test
    void 홈_조회는_별도_DTO를_공개한다() {
        JsonNode operation = document.at("/paths/~1api~1me~1favorites/get");

        assertThat(operation.path("operationId").asString()).isEqualTo("getMyFavoriteOverview");
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asString())
            .isEqualTo("#/components/schemas/FavoriteOverviewResponse");
        assertThat(operation.path("parameters")).extracting(parameter -> parameter.path("name").asString())
            .containsExactly("groupId");
        assertThat(operation.path("parameters").get(0).path("required").asBoolean()).isFalse();
        assertThat(operation.at("/responses/404/content/application~1json/schema/$ref").asString())
            .isEqualTo("#/components/schemas/ErrorResponse");
    }

    @Test
    void 즐겨찾기_대상은_stopId와_direction을_공개한다() {
        JsonNode selection = document.at("/components/schemas/SaveFavoriteSelectionRequest");
        JsonNode item = document.at("/components/schemas/FavoriteResponse");

        assertThat(names(selection.path("properties"))).containsExactlyInAnyOrder(
            "routeId", "stopId", "direction", "favoriteGroupIds");
        assertThat(selection.path("required")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("routeId", "stopId", "direction", "favoriteGroupIds");
        assertThat(names(item.path("properties"))).containsExactlyInAnyOrder(
            "id", "routeId", "stopId", "direction");
        assertThat(document.at("/components/schemas/FavoriteItemResponse").isMissingNode()).isFalse();
        assertThat(document.at("/components/schemas/FavoriteCardResponse").isMissingNode()).isTrue();
    }

    @Test
    void 전체_순서_저장은_그룹과_그룹별_즐겨찾기_ID를_한번에_받는다() {
        JsonNode operation = document.at("/paths/~1api~1me~1favorite-order/put");
        JsonNode request = document.at("/components/schemas/SaveFavoriteOrderRequest");
        JsonNode group = document.at("/components/schemas/FavoriteGroupOrderRequest");

        assertThat(operation.path("operationId").asString()).isEqualTo("saveMyFavoriteOrder");
        assertThat(operation.at("/responses/204").isMissingNode()).isFalse();
        assertThat(names(request.path("properties"))).containsExactly("groups");
        assertThat(names(group.path("properties"))).containsExactlyInAnyOrder("groupId", "favoriteIds");
    }

    @Test
    void 접근_차량은_watch_필드와_좌석_예보를_유지하고_ETA만_추가한다() {
        JsonNode favoriteVehicle = document.at("/components/schemas/FavoriteApproachingVehicleResponse/properties");
        JsonNode watchVehicle = document.at("/components/schemas/ApproachingVehicleResponse/properties");

        assertThat(names(favoriteVehicle)).containsExactlyInAnyOrder(
            "vehicleId", "horizonStops", "forecast", "eta");
        assertThat(favoriteVehicle.at("/forecast/$ref")).isEqualTo(watchVehicle.at("/forecast/$ref"));
        assertThat(favoriteVehicle.at("/horizonStops/type")).isEqualTo(watchVehicle.at("/horizonStops/type"));
        assertThat(favoriteVehicle.at("/horizonStops/minimum"))
            .isEqualTo(watchVehicle.at("/horizonStops/minimum"));
    }

    @Test
    void 노선과_정류장은_화면에_필요한_watch_필드만_준다() {
        JsonNode route = document.at("/components/schemas/FavoriteBoardRouteResponse/properties");
        JsonNode stop = document.at("/components/schemas/FavoriteStopResponse/properties");

        assertThat(names(route)).containsExactlyInAnyOrder("id", "displayName", "terminalStopName");
        assertThat(names(stop)).containsExactlyInAnyOrder("stopId", "name", "direction");
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}

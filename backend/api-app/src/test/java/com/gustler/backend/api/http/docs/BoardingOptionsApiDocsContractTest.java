package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
class BoardingOptionsApiDocsContractTest {
    private static final String PATH = "/api/v1/routes/{routeId}/stations/{stationId}/boarding-options";
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
        contract = json.readTree(Files.readString(Path.of(System.getProperty("boarding.openapi.contract"))));
    }

    @Test
    void 생성한_문서의_경로와_스키마가_프론트_공유명세와_같다() {
        // when
        JsonNode source = contract.at("/paths").path(PATH).path("get");
        JsonNode actual = generated.at("/paths").path(PATH).path("get");

        // then
        assertThat(actual.path("operationId")).isEqualTo(source.path("operationId"));
        assertThat(actual.path("parameters")).isEqualTo(source.path("parameters"));
        assertThat(actual.path("responses")).isEqualTo(source.path("responses"));
        for (Entry<String, JsonNode> schema : contract.at("/components/schemas").properties()) {
            assertThat(generated.at("/components/schemas").path(schema.getKey())).as(schema.getKey())
                .isEqualTo(schema.getValue());
        }
    }
}

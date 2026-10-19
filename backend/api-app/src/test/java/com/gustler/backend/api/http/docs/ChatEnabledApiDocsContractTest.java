package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.chat.infrastructure.mongo.ChatMongoContainer;
import com.gustler.backend.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
@ActiveProfiles("api-docs-local")
class ChatEnabledApiDocsContractTest {

    @Container
    private static final ChatMongoContainer MONGO = new ChatMongoContainer();

    @DynamicPropertySource
    static void chatProperties(DynamicPropertyRegistry registry) {
        registry.add("chat.enabled", () -> "true");
        registry.add("chat.mongodb-uri", MONGO::getConnectionString);
    }

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 실제_채팅_가용성_HTTP만_명세에_추가하고_WebSocket은_별도_계약으로_둔다() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        String body = mockMvc.perform(get("/api/docs/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/chat/rooms/{routeId}'].get.responses['200']").exists())
            .andExpect(jsonPath("$.paths['/api/chat/rooms/{routeId}'].get.responses['200'].content['application/json']").exists())
            .andExpect(jsonPath("$.paths['/api/chat/rooms/{routeId}'].get.responses['404']").exists())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode paths = objectMapper.readTree(body).path("paths");
        assertThat(paths.size()).isEqualTo(7);
        assertThat(body).doesNotContain("/api/chat/rooms/{routeId}/stream");
    }
}

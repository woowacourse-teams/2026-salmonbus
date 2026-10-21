package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ActiveProfiles("api-docs-local")
class AuthDisabledApiDocsTest {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Test
    void 로그인이_꺼져_있으면_명세에_로그인_경로가_없다() throws Exception {
        // given
        String body = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/docs/openapi"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // when
        JsonNode paths = json.readTree(body).path("paths");

        // then
        assertThat(paths.propertyNames())
            .noneMatch(path -> path.startsWith("/api/auth/") || path.startsWith("/api/me"));
    }
}

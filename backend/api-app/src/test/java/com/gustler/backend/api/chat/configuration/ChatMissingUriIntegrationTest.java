package com.gustler.backend.api.chat.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.chat.controller.ChatAvailabilityHandler;
import com.gustler.backend.api.chat.controller.ChatWebSocketHandler;
import com.gustler.backend.support.IntegrationTest;
import com.mongodb.client.MongoClient;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@TestPropertySource(properties = {"chat.enabled=true", "chat.mongodb-uri="})
class ChatMissingUriIntegrationTest {

    private static final String MISSING_URI_WARNING = "chat.enabled=true 인데 chat.mongodb-uri 가 비어 있다";

    private final ApplicationContext context;
    private final MockMvc mockMvc;

    @Autowired
    ChatMissingUriIntegrationTest(WebApplicationContext context) {
        this.context = context;
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 켜져_있어도_URI가_없으면_채팅만_꺼진_채로_뜨고_WARN을_한_번만_남긴다(CapturedOutput output) throws Exception {
        // when
        mockMvc.perform(get("/api/chat/rooms/204000057"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mockMvc.perform(get("/readyz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        // then
        assertThat(context.getBeansOfType(ChatConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatAvailabilityHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatWebSocketHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(MongoClient.class)).isEmpty();
        assertThat(output.getAll().split(Pattern.quote(MISSING_URI_WARNING), -1)).hasSize(2);
    }
}

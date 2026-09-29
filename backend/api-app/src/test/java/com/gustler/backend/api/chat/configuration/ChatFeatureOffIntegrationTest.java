package com.gustler.backend.api.chat.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.chat.controller.ChatAvailabilityHandler;
import com.gustler.backend.api.chat.controller.ChatWebSocketHandler;
import com.gustler.backend.support.IntegrationTest;
import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@IntegrationTest
class ChatFeatureOffIntegrationTest {

    private final WebApplicationContext webContext;
    private final ApplicationContext context;
    private MockMvc mockMvc;

    @Autowired
    ChatFeatureOffIntegrationTest(WebApplicationContext webContext, ApplicationContext context) {
        this.webContext = webContext;
        this.context = context;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    }

    @Test
    void 기본_설정에서는_채팅_경로가_404이고_채팅_Mongo_WebSocket_빈이_없다() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/204000057"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        assertThat(context.getBeansOfType(ChatAvailabilityHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatWebSocketHandler.class)).isEmpty();
        assertThat(context.getBeansOfType(MongoClient.class)).isEmpty();
    }

    @Test
    void 기본_설정에서는_WebSocket_매핑과_하트비트와_Mongo_스레드가_없다() throws Exception {
        // when
        mockMvc.perform(get("/api/chat/rooms/204000057/stream"))
            .andExpect(status().isNotFound());

        // then
        assertThat(context.containsBean("webSocketHandlerMapping")).isFalse();
        assertThat(context.getBeansOfType(WebSocketConfigurer.class)).isEmpty();
        assertThat(context.getBeansOfType(ServletServerContainerFactoryBean.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatConfiguration.ChatHeartbeat.class)).isEmpty();
        assertThat(mongoMonitorThreadsAfterSettling()).isEmpty();
    }

    private List<String> mongoMonitorThreadsAfterSettling() throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<String> names = mongoMonitorThreads();
        while (!names.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100L);
            names = mongoMonitorThreads();
        }
        return names;
    }

    private List<String> mongoMonitorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .filter(name -> name.startsWith("cluster-"))
            .toList();
    }
}

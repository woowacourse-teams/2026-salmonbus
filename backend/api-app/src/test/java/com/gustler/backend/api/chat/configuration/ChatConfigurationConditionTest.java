package com.gustler.backend.api.chat.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.chat.application.ChatService;
import com.gustler.backend.api.chat.controller.ChatAvailabilityHandler;
import com.gustler.backend.api.chat.controller.ChatClientAddressInterceptor;
import com.gustler.backend.api.chat.controller.ChatConnectionLimiter;
import com.gustler.backend.api.chat.controller.ChatWebSocketHandler;
import com.mongodb.client.MongoClient;
import jakarta.websocket.server.ServerContainer;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ChatConfigurationConditionTest {

    private static final String MISSING_URI_WARNING = "chat.enabled=true 인데 chat.mongodb-uri 가 비어 있다";
    private static final String MALFORMED_URI_WARNING = "chat.enabled=true 인데 chat.mongodb-uri 형식이 올바르지 않다";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withUserConfiguration(ChatConfiguration.class)
        .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
        .withBean(Clock.class, Clock::systemUTC)
        .withInitializer(context -> context.getServletContext()
            .setAttribute(ServerContainer.class.getName(), mock(ServerContainer.class)));

    @Test
    void 기본값에서는_채팅_객체와_Mongo_연결을_하나도_만들지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertChatIsOff(context);
        });
    }

    @Test
    void 명시적으로_false여도_채팅은_꺼져_있다() {
        contextRunner.withPropertyValues("chat.enabled=false", "chat.mongodb-uri=mongodb://127.0.0.1:27017")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertChatIsOff(context);
            });
    }

    @Test
    void 켜져_있어도_URI가_비어_있으면_채팅만_끈_채로_뜨고_WARN을_한_번_남긴다(CapturedOutput output) {
        // when
        contextRunner.withPropertyValues("chat.enabled=true", "chat.mongodb-uri=")
            .run(context -> {
                // then
                assertThat(context).hasNotFailed();
                assertChatIsOff(context);
            });

        // then
        assertThat(occurrences(output.getOut(), MISSING_URI_WARNING)).isEqualTo(1);
    }

    @Test
    void 켜져_있어도_URI_형식이_틀리면_채팅만_끈_채로_뜨고_값은_로그에_남기지_않는다(CapturedOutput output) {
        // when
        contextRunner.withPropertyValues(
                "chat.enabled=true", "chat.mongodb-uri=mongdb://chat:typo-secret@127.0.0.1:27017/salmonbus_chat"
            )
            .run(context -> {
                // then
                assertThat(context).hasNotFailed();
                assertChatIsOff(context);
            });

        // then
        assertThat(occurrences(output.getOut(), MALFORMED_URI_WARNING)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("typo-secret");
    }

    @Test
    void 켜져_있어도_URI가_공백뿐이면_채팅만_끈_채로_뜬다(CapturedOutput output) {
        // when
        contextRunner.withPropertyValues("chat.enabled=true")
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("blankUri", Map.of("chat.mongodb-uri", "   "))
            ))
            .run(context -> {
                // then
                assertThat(context).hasNotFailed();
                assertChatIsOff(context);
            });

        // then
        assertThat(occurrences(output.getOut(), MISSING_URI_WARNING)).isEqualTo(1);
    }

    @Test
    void 켜져_있고_URI가_있으면_채팅_객체를_만들고_기동_중에_Mongo를_기다리지_않는다(CapturedOutput output)
        throws IOException {
        // given
        String uri = "mongodb://127.0.0.1:" + closedPort() + "/?serverSelectionTimeoutMS=60000";

        // when
        assertTimeout(Duration.ofSeconds(20), () ->
            contextRunner.withPropertyValues("chat.enabled=true", "chat.mongodb-uri=" + uri)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatConfiguration.class);
                    assertThat(context).hasSingleBean(ChatProperties.class);
                    assertThat(context).hasSingleBean(MongoClient.class);
                    assertThat(context).hasSingleBean(ChatService.class);
                    assertThat(context).hasSingleBean(ChatAvailabilityHandler.class);
                    assertThat(context).hasSingleBean(ChatWebSocketHandler.class);
                    assertThat(context).hasSingleBean(ChatConnectionLimiter.class);
                    assertThat(context).hasSingleBean(ChatClientAddressInterceptor.class);
                    assertThat(context).hasSingleBean(WebSocketConfigurer.class);
                    assertThat(context).hasSingleBean(ServletServerContainerFactoryBean.class);
                    assertThat(context).hasSingleBean(ChatConfiguration.ChatHeartbeat.class);
                    assertThat(context).hasBean("webSocketHandlerMapping");
                })
        );

        // then
        assertThat(output.getOut()).doesNotContain(MISSING_URI_WARNING);
    }

    private void assertChatIsOff(AssertableWebApplicationContext context) {
        assertThat(context).doesNotHaveBean(ChatConfiguration.class);
        assertThat(context).doesNotHaveBean(ChatProperties.class);
        assertThat(context).doesNotHaveBean(ChatAvailabilityHandler.class);
        assertThat(context).doesNotHaveBean(ChatWebSocketHandler.class);
        assertThat(context).doesNotHaveBean(MongoClient.class);
        assertThat(context).doesNotHaveBean(WebSocketConfigurer.class);
        assertThat(context).doesNotHaveBean(ServletServerContainerFactoryBean.class);
        assertThat(context).doesNotHaveBean(ChatConfiguration.ChatHeartbeat.class);
        assertThat(context).doesNotHaveBean("webSocketHandlerMapping");
    }

    private int occurrences(String text, String fragment) {
        return text.split(Pattern.quote(fragment), -1).length - 1;
    }

    private int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

package com.gustler.backend.api.chat.configuration;

import com.gustler.backend.api.chat.application.ChatIdentityService;
import com.gustler.backend.api.chat.application.ChatMessageRepository;
import com.gustler.backend.api.chat.application.ChatRoomCatalog;
import com.gustler.backend.api.chat.application.ChatRouteSource;
import com.gustler.backend.api.chat.application.ChatService;
import com.gustler.backend.api.chat.controller.ChatAvailabilityHandler;
import com.gustler.backend.api.chat.controller.ChatClientAddressInterceptor;
import com.gustler.backend.api.chat.controller.ChatConnectionLimiter;
import com.gustler.backend.api.chat.controller.ChatFrameCodec;
import com.gustler.backend.api.chat.controller.ChatWebSocketHandler;
import com.gustler.backend.api.chat.infrastructure.jdbc.JdbcChatRouteSource;
import com.gustler.backend.api.chat.infrastructure.mongo.ChatMongoClientSettings;
import com.gustler.backend.api.chat.infrastructure.mongo.MongoChatMessageRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.Clock;
import org.bson.Document;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Conditional(ChatEnabledCondition.class)
@EnableConfigurationProperties(ChatProperties.class)
@EnableWebSocket
@EnableScheduling
public class ChatConfiguration {

    @Bean(destroyMethod = "close")
    MongoClient chatMongoClient(ChatProperties properties) {
        return MongoClients.create(ChatMongoClientSettings.from(properties.getMongodbUri()));
    }

    @Bean
    MongoCollection<Document> chatMessagesCollection(MongoClient chatMongoClient) {
        return chatMongoClient.getDatabase("salmonbus_chat").getCollection("messages");
    }

    @Bean
    MongoChatMessageRepository chatMessageRepository(MongoCollection<Document> chatMessagesCollection) {
        return new MongoChatMessageRepository(chatMessagesCollection);
    }

    @Bean
    JdbcChatRouteSource chatRouteSource(JdbcClient jdbcClient) {
        return new JdbcChatRouteSource(jdbcClient);
    }

    @Bean
    ChatRoomCatalog chatRoomCatalog(ChatRouteSource chatRouteSource, Clock clock) {
        return new ChatRoomCatalog(chatRouteSource, clock);
    }

    @Bean
    ChatIdentityService chatIdentityService() {
        return new ChatIdentityService();
    }

    @Bean
    ChatService chatService(ChatMessageRepository repository, Clock clock) {
        return new ChatService(repository, clock);
    }

    @Bean
    ChatFrameCodec chatFrameCodec(ObjectMapper objectMapper) {
        return new ChatFrameCodec(objectMapper);
    }

    @Bean
    ChatConnectionLimiter chatConnectionLimiter() {
        return new ChatConnectionLimiter();
    }

    @Bean
    ChatClientAddressInterceptor chatClientAddressInterceptor() {
        return new ChatClientAddressInterceptor();
    }

    @Bean
    ChatWebSocketHandler chatWebSocketHandler(
        ChatRoomCatalog rooms,
        ChatIdentityService identities,
        ChatService service,
        ChatFrameCodec codec,
        ChatConnectionLimiter connections,
        Clock clock
    ) {
        return new ChatWebSocketHandler(rooms, identities, service, codec, connections, clock);
    }

    @Bean
    ChatAvailabilityHandler chatAvailabilityHandler(ChatRoomCatalog rooms) {
        return new ChatAvailabilityHandler(rooms);
    }

    @Bean
    RouterFunction<ServerResponse> chatAvailabilityRoute(ChatAvailabilityHandler handler) {
        return RouterFunctions.route()
            .GET("/api/chat/rooms/{routeId}", handler::availability)
            .build();
    }

    @Bean
    WebSocketConfigurer chatWebSocketConfigurer(
        ChatWebSocketHandler handler,
        ChatClientAddressInterceptor clientAddressInterceptor,
        ChatProperties properties
    ) {
        return new WebSocketConfigurer() {
            @Override
            public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
                registry.addHandler(handler, "/api/chat/rooms/{routeId}/stream")
                    .addInterceptors(clientAddressInterceptor)
                    .setAllowedOrigins(properties.getAllowedOrigins().toArray(String[]::new));
            }
        };
    }

    @Bean
    ServletServerContainerFactoryBean chatWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(ChatWebSocketHandler.MAX_FRAME_BYTES);
        container.setMaxBinaryMessageBufferSize(ChatWebSocketHandler.MAX_FRAME_BYTES);
        return container;
    }

    @Bean
    ChatHeartbeat chatHeartbeat(ChatWebSocketHandler handler) {
        return new ChatHeartbeat(handler);
    }

    public static final class ChatHeartbeat {
        private final ChatWebSocketHandler handler;

        private ChatHeartbeat(ChatWebSocketHandler handler) {
            this.handler = handler;
        }

        @Scheduled(fixedRate = 1_000L)
        public void pulse() {
            handler.heartbeat();
        }

        @Scheduled(fixedRate = 60_000L, initialDelay = 60_000L)
        public void summarize() {
            handler.summarize();
        }
    }
}

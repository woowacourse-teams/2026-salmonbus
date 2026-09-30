package com.gustler.backend.api.chat.configuration;

import com.gustler.backend.api.chat.application.ChatRouteSource;
import com.gustler.backend.api.chat.application.ChatTestRoutes;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ChatTestRoutesConfiguration {

    @Bean
    @Primary
    ChatRouteSource testChatRouteSource() {
        return ChatTestRoutes.source();
    }
}

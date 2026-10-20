package com.gustler.backend.api.chat.configuration;

import com.mongodb.ConnectionString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * MongoDB 연결 설정으로 채팅 구성의 활성 조건을 판단한다.
 * 잘못된 설정을 연결 가능한 상태로 간주하지 않는다.
 */
public final class ChatEnabledCondition extends SpringBootCondition {
    static final String ENABLED = "chat.enabled";
    static final String MONGODB_URI = "chat.mongodb-uri";

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatEnabledCondition.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!"true".equalsIgnoreCase(environment.getProperty(ENABLED))) {
            return ConditionOutcome.noMatch(ENABLED + " is not true");
        }
        String uri = environment.getProperty(MONGODB_URI, "");
        if (uri.isBlank()) {
            LOGGER.warn("chat.enabled=true 인데 chat.mongodb-uri 가 비어 있다. 채팅만 끈 채로 뜬다");
            return ConditionOutcome.noMatch(MONGODB_URI + " is empty");
        }
        if (!isValidConnectionString(uri)) {
            LOGGER.warn("chat.enabled=true 인데 chat.mongodb-uri 형식이 올바르지 않다. 채팅만 끈 채로 뜬다");
            return ConditionOutcome.noMatch(MONGODB_URI + " is malformed");
        }
        return ConditionOutcome.match(ENABLED + " is true and " + MONGODB_URI + " is set");
    }

    private boolean isValidConnectionString(String uri) {
        try {
            new ConnectionString(uri.strip());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

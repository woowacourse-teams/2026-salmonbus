package com.gustler.backend.api.chat.configuration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 채팅 연결 주소·허용 출처·전체 및 주소별 연결 제한을 바인딩한다.
 * 프레임워크의 가변 속성 바인딩을 위해 클래스를 사용한다.
 */
@ConfigurationProperties("chat")
public final class ChatProperties {
    private String mongodbUri;
    private List<String> allowedOrigins = new ArrayList<>();
    private int maxConnections = 1_000;
    private int maxConnectionsPerAddress = 30;

    public String getMongodbUri() {
        return mongodbUri;
    }

    public void setMongodbUri(String mongodbUri) {
        this.mongodbUri = mongodbUri;
    }

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = new ArrayList<>(allowedOrigins);
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(final int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerAddress() {
        return maxConnectionsPerAddress;
    }

    public void setMaxConnectionsPerAddress(final int maxConnectionsPerAddress) {
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }
}

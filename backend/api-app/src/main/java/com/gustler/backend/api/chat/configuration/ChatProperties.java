package com.gustler.backend.api.chat.configuration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

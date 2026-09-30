package com.gustler.backend.api.chat.application;

import java.util.List;

public record ChatRoute(String routeId, String displayName, List<String> stopNames) {

    public ChatRoute {
        stopNames = List.copyOf(stopNames);
    }
}

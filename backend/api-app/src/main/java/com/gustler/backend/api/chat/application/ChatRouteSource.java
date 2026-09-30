package com.gustler.backend.api.chat.application;

import java.util.List;

public interface ChatRouteSource {

    List<ChatRoute> findCurrentRoutes();
}

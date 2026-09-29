package com.gustler.backend.api.chat.controller;

import com.gustler.backend.api.chat.application.ChatRoom;
import com.gustler.backend.api.chat.application.ChatRoomCatalog;
import com.gustler.backend.api.chat.application.ChatService;
import com.gustler.backend.api.chat.dto.ChatRoomResponse;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class ChatAvailabilityHandler {

    private final ChatRoomCatalog rooms;

    public ChatAvailabilityHandler(ChatRoomCatalog rooms) {
        this.rooms = rooms;
    }

    public ServerResponse availability(ServerRequest request) {
        return rooms.find(request.pathVariable("routeId"))
            .map(this::available)
            .orElseGet(() -> ServerResponse.notFound()
                .cacheControl(CacheControl.noStore())
                .build());
    }

    private ServerResponse available(ChatRoom room) {
        return ServerResponse.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ChatRoomResponse(
                room.routeId(),
                room.displayName(),
                ChatService.HISTORY_SIZE,
                ChatService.MAX_BODY_CODE_POINTS
            ));
    }
}

package com.gustler.backend.api.chat.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ChatRoomCatalog {

    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);

    private final ChatRouteSource source;
    private final Clock clock;
    private volatile Snapshot snapshot;

    public ChatRoomCatalog(ChatRouteSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public Optional<ChatRoom> find(String routeId) {
        return Optional.ofNullable(current().rooms().get(routeId));
    }

    private Snapshot current() {
        Instant now = clock.instant();
        Snapshot loaded = snapshot;
        if (loaded != null && now.isBefore(loaded.expiresAt())) {
            return loaded;
        }
        synchronized (this) {
            loaded = snapshot;
            if (loaded != null && now.isBefore(loaded.expiresAt())) {
                return loaded;
            }
            try {
                snapshot = new Snapshot(rooms(source.findCurrentRoutes()), now.plus(REFRESH_INTERVAL));
            } catch (RuntimeException exception) {
                if (loaded == null) {
                    throw exception;
                }
                snapshot = new Snapshot(loaded.rooms(), now.plus(RETRY_INTERVAL));
            }
            return snapshot;
        }
    }

    private static Map<String, ChatRoom> rooms(List<ChatRoute> routes) {
        return routes.stream()
            .map(ChatRoomCatalog::room)
            .collect(Collectors.toUnmodifiableMap(ChatRoom::routeId, Function.identity(), (first, second) -> first));
    }

    private static ChatRoom room(ChatRoute route) {
        List<String> stops = ChatNicknameStops.from(route.stopNames());
        return new ChatRoom(route.routeId(), route.displayName(), stops.isEmpty() ? List.of(route.displayName()) : stops);
    }

    private record Snapshot(Map<String, ChatRoom> rooms, Instant expiresAt) {
    }
}

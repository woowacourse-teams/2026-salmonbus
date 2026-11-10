package com.gustler.backend.api.push.application;

import com.gustler.backend.api.favorite.FavoritesSaved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RemovedFavoriteNotificationCleanup {
    private final NotificationSettingRepository settings;

    public RemovedFavoriteNotificationCleanup(NotificationSettingRepository settings) {
        this.settings = settings;
    }

    @EventListener
    public void removeSettingsOf(FavoritesSaved saved) {
        settings.deleteWithoutFavorite(saved.memberId());
    }
}

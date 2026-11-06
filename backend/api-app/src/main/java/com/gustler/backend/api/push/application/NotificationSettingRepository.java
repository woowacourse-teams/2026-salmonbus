package com.gustler.backend.api.push.application;

import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationSettingRepository {
    record SavedSetting(
        UUID id,
        String routeId,
        String stopId,
        String direction,
        String time,
        List<String> weekdays
    ) {
        public SavedSetting {
            weekdays = List.copyOf(weekdays);
        }
    }

    record DueSetting(
        UUID id,
        long memberId,
        FavoriteTarget target,
        NotificationSchedule schedule,
        Instant occurrenceAt
    ) {
    }

    Optional<Long> memberIdOf(UUID memberPublicId);

    boolean hasFavorite(long memberId, FavoriteTarget target);

    SavedSetting upsert(
        long memberId,
        FavoriteTarget target,
        NotificationSchedule schedule,
        Instant occurrenceAt,
        Instant now
    );

    List<SavedSetting> findByMemberId(long memberId);

    boolean delete(long memberId, UUID settingId);

    List<DueSetting> findDue(Instant now, int limit);

    boolean advance(UUID settingId, Instant readOccurrenceAt, Instant nextOccurrenceAt, Instant now);

    boolean deleteOrphan(UUID settingId, Instant readOccurrenceAt);
}

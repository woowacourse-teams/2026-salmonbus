package com.gustler.backend.api.push.application;

import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.InvalidNotificationScheduleException;
import com.gustler.backend.api.push.NotificationSettingNotFoundException;
import com.gustler.backend.api.push.NotificationTargetNotFavoriteException;
import com.gustler.backend.api.push.application.NotificationSettingRepository.SavedSetting;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NotificationSettingService {
    private final NotificationSettingRepository repository;
    private final Clock clock;

    public NotificationSettingService(NotificationSettingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public SavedSetting save(
        UUID memberPublicId,
        String routeId,
        String stopId,
        String direction,
        String time,
        List<String> weekdays
    ) {
        FavoriteTarget target = targetOf(routeId, stopId, direction);
        NotificationSchedule schedule = scheduleOf(time, weekdays);
        long memberId = repository.memberIdOf(memberPublicId).orElseThrow(UnauthenticatedException::new);
        if (!repository.hasFavorite(memberId, target)) {
            throw new NotificationTargetNotFavoriteException();
        }
        return repository.upsert(memberId, target, schedule, schedule.nextAfter(clock.instant()), clock.instant());
    }

    public List<SavedSetting> list(UUID memberPublicId) {
        long memberId = repository.memberIdOf(memberPublicId).orElseThrow(UnauthenticatedException::new);
        return repository.findByMemberId(memberId);
    }

    public void delete(UUID memberPublicId, UUID settingId) {
        long memberId = repository.memberIdOf(memberPublicId).orElseThrow(UnauthenticatedException::new);
        if (!repository.delete(memberId, settingId)) {
            throw new NotificationSettingNotFoundException();
        }
    }

    private static FavoriteTarget targetOf(String routeId, String stopId, String direction) {
        try {
            return new FavoriteTarget(routeId, stopId, FavoriteDirection.valueOf(direction));
        } catch (RuntimeException exception) {
            throw new InvalidFavoriteRequestException();
        }
    }

    private static NotificationSchedule scheduleOf(String time, List<String> weekdays) {
        try {
            return NotificationSchedule.parse(time, weekdays);
        } catch (IllegalArgumentException exception) {
            throw new InvalidNotificationScheduleException();
        }
    }
}

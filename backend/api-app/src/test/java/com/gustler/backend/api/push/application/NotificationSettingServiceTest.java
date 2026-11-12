package com.gustler.backend.api.push.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationSettingServiceTest {
    private static final UUID MEMBER = UUID.randomUUID();
    private static final long MEMBER_ROW = 10L;
    private static final Instant NOW = Instant.parse("2026-11-01T22:39:00Z");
    private static final String ROUTE = "234001739";
    private static final String STOP = "214002018";
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    private static final FavoriteTarget TARGET = new FavoriteTarget(ROUTE, STOP, FavoriteDirection.UP);

    private final NotificationSettingRepository repository = mock(NotificationSettingRepository.class);
    private final NotificationSettingService service = new NotificationSettingService(
        repository,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void 평일_07시40분_저장은_같은_날_차례를_넘긴다() {
        SavedSetting saved = setting(UUID.randomUUID());
        when(repository.lockMember(MEMBER)).thenReturn(Optional.of(MEMBER_ROW));
        when(repository.hasFavorite(MEMBER_ROW, TARGET)).thenReturn(true);
        when(repository.upsert(eq(MEMBER_ROW), eq(TARGET), any(), any(), eq(NOW))).thenReturn(saved);

        assertThat(service.save(MEMBER, ROUTE, STOP, "UP", "07:40", WEEKDAYS)).isEqualTo(saved);
        verify(repository).upsert(
            MEMBER_ROW,
            TARGET,
            NotificationSchedule.parse("07:40", WEEKDAYS),
            Instant.parse("2026-11-01T22:40:00Z"),
            NOW
        );
    }

    @Test
    void 시각을_08시로_바꾸면_그날_08시를_넘긴다() {
        NotificationSettingService later = new NotificationSettingService(
            repository,
            Clock.fixed(Instant.parse("2026-11-01T22:50:00Z"), ZoneOffset.UTC)
        );
        when(repository.lockMember(MEMBER)).thenReturn(Optional.of(MEMBER_ROW));
        when(repository.hasFavorite(MEMBER_ROW, TARGET)).thenReturn(true);
        when(repository.upsert(eq(MEMBER_ROW), eq(TARGET), any(), any(), any()))
            .thenReturn(setting(UUID.randomUUID()));

        later.save(MEMBER, ROUTE, STOP, "UP", "08:00", WEEKDAYS);
        verify(repository).upsert(
            MEMBER_ROW,
            TARGET,
            NotificationSchedule.parse("08:00", WEEKDAYS),
            Instant.parse("2026-11-01T23:00:00Z"),
            Instant.parse("2026-11-01T22:50:00Z")
        );
    }

    @ParameterizedTest
    @CsvSource(value = {
        "abc,214002018,UP",
        "234001739,abc,UP",
        "234001739,214002018,LEFT",
        "234001739,214002018,NULL"
    }, nullValues = "NULL")
    void 대상_형식이_틀리면_INVALID_REQUEST다(String routeId, String stopId, String direction) {
        assertThatThrownBy(() -> service.save(MEMBER, routeId, stopId, direction, "07:40", WEEKDAYS))
            .isInstanceOf(InvalidFavoriteRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 대상과_요일이_함께_틀려도_대상_오류가_먼저다() {
        assertThatThrownBy(() -> service.save(MEMBER, "abc", STOP, "UP", "07:40:00", List.of()))
            .isInstanceOf(InvalidFavoriteRequestException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"07:40:00", "7:40"})
    void 시각이_비거나_초가_있으면_일정_오류다(String time) {
        assertThatThrownBy(() -> service.save(MEMBER, ROUTE, STOP, "UP", time, WEEKDAYS))
            .isInstanceOf(InvalidNotificationScheduleException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 빈_요일과_중복_요일은_일정_오류다() {
        assertThatThrownBy(() -> service.save(MEMBER, ROUTE, STOP, "UP", "07:40", null))
            .isInstanceOf(InvalidNotificationScheduleException.class);
        assertThatThrownBy(() -> service.save(MEMBER, ROUTE, STOP, "UP", "07:40", List.of()))
            .isInstanceOf(InvalidNotificationScheduleException.class);
        assertThatThrownBy(() -> service.save(MEMBER, ROUTE, STOP, "UP", "07:40", List.of("MON", "MON")))
            .isInstanceOf(InvalidNotificationScheduleException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 즐겨찾기에_없으면_422다() {
        when(repository.lockMember(MEMBER)).thenReturn(Optional.of(MEMBER_ROW));
        when(repository.hasFavorite(MEMBER_ROW, TARGET)).thenReturn(false);
        assertThatThrownBy(() -> service.save(MEMBER, ROUTE, STOP, "UP", "07:40", WEEKDAYS))
            .isInstanceOf(NotificationTargetNotFavoriteException.class);
        verify(repository, never()).upsert(anyLong(), any(), any(), any(), any());
    }

    @Test
    void 회원이_없으면_저장과_조회와_삭제가_401이다() {
        UUID settingId = UUID.randomUUID();
        when(repository.lockMember(MEMBER)).thenReturn(Optional.empty());
        when(repository.memberIdOf(MEMBER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(MEMBER, ROUTE, STOP, "UP", "07:40", WEEKDAYS))
            .isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.list(MEMBER)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.delete(MEMBER, settingId)).isInstanceOf(UnauthenticatedException.class);
        verify(repository, never()).upsert(anyLong(), any(), any(), any(), any());
        verify(repository, never()).findByMemberId(anyLong());
        verify(repository, never()).delete(anyLong(), any());
    }

    @Test
    void 내_설정이_아니면_404다() {
        UUID settingId = UUID.randomUUID();
        when(repository.memberIdOf(MEMBER)).thenReturn(Optional.of(MEMBER_ROW));
        when(repository.delete(MEMBER_ROW, settingId)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(MEMBER, settingId))
            .isInstanceOf(NotificationSettingNotFoundException.class);
    }

    @Test
    void 목록은_회원_설정을_그대로_돌려준다() {
        SavedSetting saved = setting(UUID.randomUUID());
        when(repository.memberIdOf(MEMBER)).thenReturn(Optional.of(MEMBER_ROW));
        when(repository.findByMemberId(MEMBER_ROW)).thenReturn(List.of(saved));
        assertThat(service.list(MEMBER)).containsExactly(saved);
    }

    private static SavedSetting setting(UUID id) {
        return new SavedSetting(id, ROUTE, STOP, "UP", "07:40", WEEKDAYS);
    }
}

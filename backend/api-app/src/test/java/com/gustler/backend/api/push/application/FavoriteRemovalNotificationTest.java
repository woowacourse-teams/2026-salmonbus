package com.gustler.backend.api.push.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.favorite.application.FavoriteGroupRepository;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class FavoriteRemovalNotificationTest {
    private static final Instant NOW = Instant.parse("2026-11-03T22:00:00Z");
    private static final FavoriteTarget MORNING = new FavoriteTarget("234001739", "214002018", FavoriteDirection.UP);
    private static final FavoriteTarget EVENING = new FavoriteTarget("234001739", "214002019", FavoriteDirection.DOWN);
    private static final NotificationSchedule WEEKDAYS = NotificationSchedule.parse(
        "07:40",
        List.of("MON", "TUE", "WED", "THU", "FRI")
    );

    @Autowired
    private FavoriteGroupRepository favorites;
    @Autowired
    private NotificationSettingRepository settings;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void 즐겨찾기에서_빠진_대상의_알림_설정은_즐겨찾기를_저장할_때_지워진다() {
        UUID member = member("연어하나");
        long memberId = settings.memberIdOf(member).orElseThrow();
        FavoriteGroup group = group(MORNING, EVENING);
        favorites.update(member, ignored -> new MemberFavoriteGroups(List.of(group)));
        settings.upsert(memberId, MORNING, WEEKDAYS, NOW.plusSeconds(2400), NOW);
        settings.upsert(memberId, EVENING, WEEKDAYS, NOW.plusSeconds(2400), NOW);

        favorites.update(member, current -> new MemberFavoriteGroups(List.of(group.remove(MORNING))));

        assertThat(settings.findByMemberId(memberId))
            .extracting(NotificationSettingRepository.SavedSetting::stopId)
            .containsExactly(EVENING.stopId());
    }

    @Test
    void 지웠다가_다시_추가한_즐겨찾기는_알림이_꺼진_채로_시작한다() {
        UUID member = member("연어하나");
        long memberId = settings.memberIdOf(member).orElseThrow();
        FavoriteGroup group = group(MORNING);
        favorites.update(member, ignored -> new MemberFavoriteGroups(List.of(group)));
        settings.upsert(memberId, MORNING, WEEKDAYS, NOW.plusSeconds(2400), NOW);

        favorites.update(member, current -> new MemberFavoriteGroups(List.of(group.remove(MORNING))));
        favorites.update(member, current -> new MemberFavoriteGroups(List.of(group)));

        assertThat(settings.findByMemberId(memberId)).isEmpty();
    }

    @Test
    void 다른_회원의_알림_설정은_남는다() {
        UUID owner = member("연어하나");
        UUID other = member("연어둘");
        long otherId = settings.memberIdOf(other).orElseThrow();
        favorites.update(owner, ignored -> new MemberFavoriteGroups(List.of(group(MORNING))));
        favorites.update(other, ignored -> new MemberFavoriteGroups(List.of(group(MORNING))));
        settings.upsert(otherId, MORNING, WEEKDAYS, NOW.plusSeconds(2400), NOW);

        favorites.update(owner, ignored -> new MemberFavoriteGroups(List.of(group())));

        assertThat(settings.findByMemberId(otherId)).hasSize(1);
    }

    private static FavoriteGroup group(FavoriteTarget... targets) {
        List<Favorite> entries = Arrays.stream(targets)
            .map(target -> new Favorite(new FavoriteId(UUID.randomUUID()), target))
            .toList();
        return new FavoriteGroup(new FavoriteGroupId(UUID.randomUUID()), new FavoriteGroupName("출근"), entries);
    }

    private UUID member(String nickname) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO member (public_id, nickname, created_at, last_login_at) VALUES (?, ?, ?, ?)")
            .params(id, nickname, now, now)
            .update();
        return id;
    }
}

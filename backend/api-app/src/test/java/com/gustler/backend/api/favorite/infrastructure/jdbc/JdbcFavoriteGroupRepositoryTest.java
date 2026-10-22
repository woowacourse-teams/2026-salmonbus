package com.gustler.backend.api.favorite.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcFavoriteGroupRepositoryTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T09:00:00+09:00");

    @Autowired
    private JdbcFavoriteGroupRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 회원의_그룹과_즐겨찾기를_정한_순서와_ID로_저장하고_조회한다() {
        // given
        UUID member = insertMember("연어하나");
        MemberFavoriteGroups expected = groups("출근", "퇴근");

        // when
        repository.update(member, ignored -> expected);

        // then
        assertThat(repository.findByMemberPublicId(member))
            .get()
            .usingRecursiveComparison()
            .isEqualTo(expected);
    }

    @Test
    void 그룹이_없는_회원과_없는_회원을_구분한다() {
        // given
        UUID member = insertMember("연어하나");

        // when
        Optional<MemberFavoriteGroups> existing = repository.findByMemberPublicId(member);
        Optional<MemberFavoriteGroups> missing = repository.findByMemberPublicId(UUID.randomUUID());

        // then
        assertThat(existing).get().extracting(MemberFavoriteGroups::values).asList().isEmpty();
        assertThat(missing).isEmpty();
    }

    @Test
    void 기본_그룹은_여러_번_요청해도_한_개만_만든다() {
        // given
        UUID member = insertMember("연어하나");
        FavoriteGroup firstCandidate = emptyGroup("즐겨찾기");
        FavoriteGroup secondCandidate = emptyGroup("다른 이름");

        // when
        MemberFavoriteGroups first = repository.ensureDefault(member, firstCandidate).orElseThrow();
        MemberFavoriteGroups second = repository.ensureDefault(member, secondCandidate).orElseThrow();

        // then
        assertThat(first.values()).singleElement()
            .extracting(FavoriteGroup::id, FavoriteGroup::name)
            .containsExactly(firstCandidate.id(), firstCandidate.name());
        assertThat(second.values()).singleElement()
            .extracting(FavoriteGroup::id, FavoriteGroup::name)
            .containsExactly(firstCandidate.id(), firstCandidate.name());
        assertThat(jdbcClient.sql("SELECT count(*) FROM favorite_group WHERE member_id = "
                + "(SELECT id FROM member WHERE public_id = ?)")
            .param(member).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 회원을_삭제하면_그룹과_즐겨찾기를_연쇄_삭제하고_다른_회원_데이터는_유지한다() {
        // given
        UUID deletedMember = insertMember("연어하나");
        UUID remainingMember = insertMember("연어둘");
        repository.update(deletedMember, ignored -> groups("출근", "퇴근"));
        repository.update(remainingMember, ignored -> groups("주말"));

        // when
        jdbcClient.sql("DELETE FROM member WHERE public_id = ?").param(deletedMember).update();

        // then
        assertThat(jdbcClient.sql("SELECT count(*) FROM favorite_group").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT count(*) FROM favorite").query(Long.class).single()).isEqualTo(2);
        assertThat(repository.findByMemberPublicId(remainingMember)).isPresent();
    }

    @Test
    void 없는_회원은_변경하지_않는다() {
        assertThat(repository.update(UUID.randomUUID(), ignored -> groups("출근"))).isEmpty();
        assertThat(jdbcClient.sql("SELECT count(*) FROM favorite_group").query(Long.class).single()).isZero();
    }

    private MemberFavoriteGroups groups(
        String... names
    ) {
        return new MemberFavoriteGroups(java.util.stream.IntStream.range(0, names.length)
            .mapToObj(index -> group(names[index], index))
            .toList());
    }

    private FavoriteGroup group(
        String name,
        int index
    ) {
        return new FavoriteGroup(
            new FavoriteGroupId(uuid("group-" + name)),
            new FavoriteGroupName(name),
            List.of(
                new Favorite(new FavoriteId(uuid("favorite-" + name + "-1")),
                    new FavoriteTarget("204000057", "27710232" + index, FavoriteDirection.UP)),
                new Favorite(new FavoriteId(uuid("favorite-" + name + "-2")),
                    new FavoriteTarget("204000057", "27710233" + index, FavoriteDirection.DOWN))
            )
        );
    }

    private FavoriteGroup emptyGroup(String name) {
        return new FavoriteGroup(new FavoriteGroupId(UUID.randomUUID()), new FavoriteGroupName(name), List.of());
    }

    private UUID insertMember(
        String nickname
    ) {
        UUID publicId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO member (public_id, nickname, created_at, last_login_at)
                VALUES (?, ?, ?, ?)
                """)
            .params(publicId, nickname, NOW, NOW)
            .update();
        return publicId;
    }

    private static UUID uuid(
        String value
    ) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

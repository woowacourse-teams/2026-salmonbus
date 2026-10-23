package com.gustler.backend.api.favorite.domain;

import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.OTHER_TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.THIRD_TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.favoriteId;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.group;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.FavoriteLimitExceededException;
import org.junit.jupiter.api.Test;

class FavoriteGroupTest {
    @Test
    void 즐겨찾기는_두_개까지만_등록한다() {
        // given
        FavoriteGroup group = group("출근", TARGET, OTHER_TARGET);

        assertThatThrownBy(() -> group.add(new Favorite(favoriteId("세번째"), THIRD_TARGET)))
            .isInstanceOf(FavoriteLimitExceededException.class);
    }

    @Test
    void 같은_노선과_정류장과_방향을_두_번_등록해도_한_개다() {
        // given
        FavoriteGroup group = group("출근", TARGET);

        // when
        FavoriteGroup actual = group.add(new Favorite(favoriteId("중복"), TARGET));

        // then
        assertThat(actual).isSameAs(group);
        assertThat(actual.favorites()).hasSize(1);
    }

    @Test
    void 같은_노선과_정류장도_방향이_다르면_따로_등록한다() {
        FavoriteGroup group = group("출근", TARGET);
        FavoriteTarget opposite = new FavoriteTarget(TARGET.routeId(), TARGET.stopId(), FavoriteDirection.DOWN);

        FavoriteGroup actual = group.add(new Favorite(favoriteId("반대방향"), opposite));

        assertThat(actual.favorites()).extracting(Favorite::target).containsExactly(TARGET, opposite);
    }

    @Test
    void 이름을_바꿔도_그룹_ID와_즐겨찾기는_유지한다() {
        // given
        FavoriteGroup group = group("출근", TARGET);

        // when
        FavoriteGroup actual = group.rename(new FavoriteGroupName("평일 출근"));

        // then
        assertThat(actual.name().value()).isEqualTo("평일 출근");
        assertThat(actual.id()).isEqualTo(group.id());
        assertThat(actual.favorites()).isEqualTo(group.favorites());
    }
}

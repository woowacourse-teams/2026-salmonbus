package com.gustler.backend.api.favorite.domain;

import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.OTHER_TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.emptyGroup;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.group;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.FavoriteLimitExceededException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FavoriteSelectionPolicyTest {
    private final FavoriteSelectionPolicy policy = new FavoriteSelectionPolicy();

    @Test
    void 이미_등록된_그룹은_유지하고_새로_선택한_그룹만_등록한다() {
        // given
        FavoriteGroup commute = group("출근", TARGET);
        FavoriteGroup weekend = emptyGroup("주말");
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(commute, weekend));

        // when
        FavoriteSelectionPlan actual = policy.plan(
            groups,
            List.of(commute.id(), weekend.id()),
            TARGET
        );

        // then
        assertThat(actual.additions()).containsExactly(weekend.id());
        assertThat(actual.removals()).isEmpty();
    }

    @Test
    void 같은_선택을_반복하면_변경할_항목이_없다() {
        // given
        FavoriteGroup commute = group("출근", TARGET);
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(commute));

        // when
        FavoriteSelectionPlan actual = policy.plan(groups, List.of(commute.id()), TARGET);

        // then
        assertThat(actual.unchanged()).isTrue();
    }

    @Test
    void 새_등록_대상_중_가득_찬_그룹이_있으면_전체_계획을_거절한다() {
        // given
        FavoriteGroup available = emptyGroup("출근");
        FavoriteGroup full = group("주말", OTHER_TARGET, FavoriteDomainFixture.THIRD_TARGET);
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(available, full));

        // when & then
        assertThatThrownBy(() -> policy.plan(groups, List.of(available.id(), full.id()), TARGET))
            .isInstanceOf(FavoriteLimitExceededException.class);
    }

    @Test
    void 선택에서_빠진_그룹의_기존_즐겨찾기를_해제한다() {
        // given
        FavoriteGroup commute = group("출근", TARGET);
        FavoriteGroup weekend = group("주말", TARGET);
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(commute, weekend));

        // when
        FavoriteSelectionPlan actual = policy.plan(groups, List.of(commute.id()), TARGET);

        // then
        assertThat(actual.additions()).isEmpty();
        assertThat(actual.removals()).containsExactly(weekend.id());
    }
}

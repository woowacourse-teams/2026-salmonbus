package com.gustler.backend.api.favorite.domain;

import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.emptyGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.FavoriteGroupLimitExceededException;
import com.gustler.backend.api.favorite.LastFavoriteGroupDeletionException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberFavoriteGroupsTest {
    @Test
    void 그룹이_여러_개면_첫_번째_그룹도_삭제할_수_있다() {
        FavoriteGroup first = emptyGroup("출근");
        FavoriteGroup second = emptyGroup("퇴근");
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(first, second));

        assertThat(groups.remove(first.id()).values()).containsExactly(second);
    }

    @Test
    void 마지막_그룹은_삭제할_수_없다() {
        FavoriteGroup first = emptyGroup("출근");
        FavoriteGroup second = emptyGroup("퇴근");
        MemberFavoriteGroups remaining = new MemberFavoriteGroups(List.of(first, second)).remove(first.id());

        assertThatThrownBy(() -> remaining.remove(second.id()))
            .isInstanceOf(LastFavoriteGroupDeletionException.class);
    }

    @Test
    void 즐겨찾기_그룹은_세_개까지만_만든다() {
        // given
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(
            emptyGroup("출근"),
            emptyGroup("퇴근"),
            emptyGroup("주말")
        ));

        assertThatThrownBy(() -> groups.append(emptyGroup("추가")))
            .isInstanceOf(FavoriteGroupLimitExceededException.class);
    }

    @Test
    void 같은_회원의_그룹_이름은_중복될_수_없다() {
        // given
        FavoriteGroup first = emptyGroup("출근");
        FavoriteGroup otherIdWithSameName = emptyGroup("퇴근").rename(new FavoriteGroupName("출근"));

        assertThatThrownBy(() -> new MemberFavoriteGroups(List.of(first, otherIdWithSameName)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 현재_그룹_ID를_빠짐없이_보내면_그_순서로_바꾼다() {
        FavoriteGroup first = emptyGroup("출근");
        FavoriteGroup second = emptyGroup("퇴근");
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(first, second));

        MemberFavoriteGroups reordered = groups.reorder(List.of(second.id(), first.id()));

        assertThat(reordered.values()).containsExactly(second, first);
    }

    @Test
    void 그룹_정렬에_현재_ID가_빠지면_거절한다() {
        FavoriteGroup first = emptyGroup("출근");
        FavoriteGroup second = emptyGroup("퇴근");
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(first, second));

        assertThatThrownBy(() -> groups.reorder(List.of(first.id())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

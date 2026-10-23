package com.gustler.backend.api.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.favorite.FavoriteGroupNameConflictException;
import com.gustler.backend.api.favorite.InvalidFavoriteOrderException;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class FavoriteGroupServiceTest {
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private final MemoryRepository repository = new MemoryRepository();
    private final FavoriteTargetResolver resolver = mock(FavoriteTargetResolver.class);
    private final DefaultFavoriteGroupService defaultGroups = new DefaultFavoriteGroupService(repository);
    private final FavoriteGroupService service = new FavoriteGroupService(repository, resolver, defaultGroups);

    @Test
    void 첫_즐겨찾기와_그룹을_한번에_만든다() {
        FavoriteTarget target = service.targetOf("204000057", "277102322", FavoriteDirection.UP);
        when(resolver.requireAvailable(target)).thenReturn(target);

        FavoriteGroup created = service.create(MEMBER_ID, " 출근 ", target);

        assertThat(created.name().value()).isEqualTo("출근");
        assertThat(created.favorites()).singleElement().extracting(favorite -> favorite.target()).isEqualTo(target);
    }

    @Test
    void 같은_이름의_그룹을_만들지_않는다() {
        service.create(MEMBER_ID, "출근", null);

        assertThatThrownBy(() -> service.create(MEMBER_ID, "출근", null))
            .isInstanceOf(FavoriteGroupNameConflictException.class);
    }

    @Test
    void 그룹_이름을_바꾸고_순서를_바꾼다() {
        FavoriteGroup first = service.create(MEMBER_ID, "출근", null);
        FavoriteGroup second = service.create(MEMBER_ID, "퇴근", null);

        service.rename(MEMBER_ID, first.id().value(), "평일");
        service.reorder(MEMBER_ID, List.of(second.id().value(), first.id().value()));

        assertThat(service.findAll(MEMBER_ID).values()).extracting(group -> group.name().value())
            .containsExactly("퇴근", "평일");
    }

    @Test
    void 일부_그룹만_보낸_정렬은_거절한다() {
        FavoriteGroup first = service.create(MEMBER_ID, "출근", null);
        service.create(MEMBER_ID, "퇴근", null);

        assertThatThrownBy(() -> service.reorder(MEMBER_ID, List.of(first.id().value())))
            .isInstanceOf(InvalidFavoriteOrderException.class);
    }

    private static final class MemoryRepository implements FavoriteGroupRepository {
        private MemberFavoriteGroups groups = new MemberFavoriteGroups(new ArrayList<>());

        @Override
        public Optional<MemberFavoriteGroups> findByMemberPublicId(UUID memberPublicId) {
            return Optional.of(groups);
        }

        @Override
        public Optional<MemberFavoriteGroups> ensureDefault(UUID memberPublicId, FavoriteGroup defaultGroup) {
            if (groups.values().isEmpty()) {
                groups = new MemberFavoriteGroups(List.of(defaultGroup));
            }
            return Optional.of(groups);
        }

        @Override
        public Optional<MemberFavoriteGroups> update(UUID memberPublicId, UnaryOperator<MemberFavoriteGroups> change) {
            groups = change.apply(groups);
            return Optional.of(groups);
        }
    }
}

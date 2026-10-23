package com.gustler.backend.api.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.favorite.FavoriteLimitExceededException;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FavoriteSelectionServiceTest {
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final FavoriteTarget TARGET = new FavoriteTarget("204000057", "277102322", FavoriteDirection.UP);
    private static final FavoriteTarget OTHER = new FavoriteTarget("204000057", "277102323", FavoriteDirection.UP);
    private final MemoryRepository repository = new MemoryRepository();
    private final FavoriteTargetResolver resolver = mock(FavoriteTargetResolver.class);
    private final FavoriteSelectionService service = new FavoriteSelectionService(repository, resolver);

    @BeforeEach
    void allowTarget() {
        when(resolver.requireAvailable(TARGET)).thenReturn(TARGET);
    }

    @Test
    void 여러_그룹_선택을_한_트랜잭션으로_반영한다() {
        FavoriteGroup first = group("출근", List.of());
        FavoriteGroup second = group("퇴근", List.of());
        repository.groups = new MemberFavoriteGroups(List.of(first, second));

        service.select(MEMBER_ID, TARGET, List.of(first.id().value(), second.id().value()));

        assertThat(repository.groups.values()).allSatisfy(group -> assertThat(group.contains(TARGET)).isTrue());
    }

    @Test
    void 새_등록_대상_중_가득_찬_그룹이_있으면_모두_롤백한다() {
        FavoriteGroup empty = group("출근", List.of());
        FavoriteGroup full = group("퇴근", List.of(favorite(OTHER),
            favorite(new FavoriteTarget("204000057", "277102324", FavoriteDirection.UP))));
        repository.groups = new MemberFavoriteGroups(List.of(empty, full));

        assertThatThrownBy(() -> service.select(MEMBER_ID, TARGET,
            List.of(empty.id().value(), full.id().value())))
            .isInstanceOf(FavoriteLimitExceededException.class);
        assertThat(repository.groups.find(empty.id()).orElseThrow().contains(TARGET)).isFalse();
    }

    @Test
    void 같은_선택을_반복해도_결과와_ID가_같다() {
        FavoriteGroup group = group("출근", List.of());
        repository.groups = new MemberFavoriteGroups(List.of(group));
        service.select(MEMBER_ID, TARGET, List.of(group.id().value()));
        UUID firstId = repository.groups.values().getFirst().favorites().getFirst().id().value();

        service.select(MEMBER_ID, TARGET, List.of(group.id().value()));

        assertThat(repository.groups.values().getFirst().favorites()).singleElement()
            .extracting(item -> item.id().value()).isEqualTo(firstId);
    }

    private static FavoriteGroup group(String name, List<Favorite> favorites) {
        return new FavoriteGroup(new FavoriteGroupId(UUID.randomUUID()), new FavoriteGroupName(name), favorites);
    }

    private static Favorite favorite(FavoriteTarget target) {
        return new Favorite(new FavoriteId(UUID.randomUUID()), target);
    }

    private static final class MemoryRepository implements FavoriteGroupRepository {
        private MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of());

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

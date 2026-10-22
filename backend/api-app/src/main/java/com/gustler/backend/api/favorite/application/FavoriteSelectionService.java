package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.favorite.FavoriteGroupNotFoundException;
import com.gustler.backend.api.favorite.InvalidFavoriteOrderException;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteSelectionPlan;
import com.gustler.backend.api.favorite.domain.FavoriteSelectionPolicy;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FavoriteSelectionService {
    private final FavoriteGroupRepository repository;
    private final FavoriteTargetResolver targetResolver;
    private final FavoriteSelectionPolicy policy = new FavoriteSelectionPolicy();

    public FavoriteSelectionService(FavoriteGroupRepository repository, FavoriteTargetResolver targetResolver) {
        this.repository = repository;
        this.targetResolver = targetResolver;
    }

    public void select(UUID memberPublicId, FavoriteTarget target, List<UUID> rawSelectedGroupIds) {
        if (rawSelectedGroupIds == null) {
            throw new InvalidFavoriteRequestException();
        }
        FavoriteTarget availableTarget = targetResolver.requireAvailable(target);
        List<FavoriteGroupId> selectedGroupIds;
        try {
            selectedGroupIds = rawSelectedGroupIds.stream().map(FavoriteGroupId::new).toList();
        } catch (RuntimeException exception) {
            throw new InvalidFavoriteRequestException();
        }
        repository.update(memberPublicId, groups -> applySelection(groups, selectedGroupIds, availableTarget))
            .orElseThrow(UnauthenticatedException::new);
    }

    public void delete(UUID memberPublicId, UUID rawGroupId, UUID rawFavoriteId) {
        FavoriteGroupId groupId = new FavoriteGroupId(rawGroupId);
        FavoriteId favoriteId = new FavoriteId(rawFavoriteId);
        repository.update(memberPublicId, groups -> {
            requireGroup(groups, groupId);
            return groups.update(groupId, group -> group.remove(favoriteId));
        }).orElseThrow(UnauthenticatedException::new);
    }

    public void reorder(UUID memberPublicId, UUID rawGroupId, List<UUID> rawFavoriteIds) {
        if (rawFavoriteIds == null) {
            throw new InvalidFavoriteOrderException();
        }
        FavoriteGroupId groupId = new FavoriteGroupId(rawGroupId);
        List<FavoriteId> favoriteIds;
        try {
            favoriteIds = rawFavoriteIds.stream().map(FavoriteId::new).toList();
        } catch (RuntimeException exception) {
            throw new InvalidFavoriteOrderException();
        }
        repository.update(memberPublicId, groups -> {
            requireGroup(groups, groupId);
            try {
                return groups.update(groupId, group -> group.reorder(favoriteIds));
            } catch (IllegalArgumentException exception) {
                throw new InvalidFavoriteOrderException();
            }
        }).orElseThrow(UnauthenticatedException::new);
    }

    private MemberFavoriteGroups applySelection(MemberFavoriteGroups groups, List<FavoriteGroupId> selected,
        FavoriteTarget target) {
        final FavoriteSelectionPlan plan;
        try {
            plan = policy.plan(groups, selected, target);
        } catch (IllegalArgumentException exception) {
            throw new InvalidFavoriteRequestException();
        }
        MemberFavoriteGroups changed = groups;
        for (FavoriteGroupId groupId : plan.removals()) {
            changed = changed.update(groupId, group -> group.remove(target));
        }
        for (FavoriteGroupId groupId : plan.additions()) {
            Favorite favorite = new Favorite(new FavoriteId(UUID.randomUUID()), target);
            changed = changed.update(groupId, group -> group.add(favorite));
        }
        return changed;
    }

    private static FavoriteGroup requireGroup(MemberFavoriteGroups groups, FavoriteGroupId groupId) {
        return groups.find(groupId).orElseThrow(FavoriteGroupNotFoundException::new);
    }
}

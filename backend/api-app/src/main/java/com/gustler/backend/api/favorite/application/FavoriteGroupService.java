package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.favorite.FavoriteGroupNameConflictException;
import com.gustler.backend.api.favorite.FavoriteGroupNotFoundException;
import com.gustler.backend.api.favorite.InvalidFavoriteGroupNameException;
import com.gustler.backend.api.favorite.InvalidFavoriteGroupOrderException;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FavoriteGroupService {
    private final FavoriteGroupRepository repository;
    private final FavoriteTargetResolver targetResolver;
    private final DefaultFavoriteGroupService defaultGroupService;

    public FavoriteGroupService(FavoriteGroupRepository repository, FavoriteTargetResolver targetResolver,
        DefaultFavoriteGroupService defaultGroupService) {
        this.repository = repository;
        this.targetResolver = targetResolver;
        this.defaultGroupService = defaultGroupService;
    }

    public MemberFavoriteGroups findAll(UUID memberPublicId) {
        return defaultGroupService.ensure(memberPublicId);
    }

    public FavoriteGroup create(UUID memberPublicId, String rawName, FavoriteTarget initialFavorite) {
        FavoriteGroupName name = nameOf(rawName);
        if (initialFavorite != null) {
            targetResolver.requireAvailable(initialFavorite);
        }
        FavoriteGroup created = new FavoriteGroup(new FavoriteGroupId(UUID.randomUUID()), name,
            initialFavorite == null ? List.of() : List.of(new Favorite(new FavoriteId(UUID.randomUUID()), initialFavorite)));
        return repository.update(memberPublicId, groups -> {
            requireUniqueName(groups, name, null);
            return groups.append(created);
        }).orElseThrow(UnauthenticatedException::new).find(created.id()).orElseThrow();
    }

    public FavoriteGroup rename(UUID memberPublicId, UUID rawGroupId, String rawName) {
        FavoriteGroupId groupId = new FavoriteGroupId(rawGroupId);
        FavoriteGroupName name = nameOf(rawName);
        return repository.update(memberPublicId, groups -> {
            requireGroup(groups, groupId);
            requireUniqueName(groups, name, groupId);
            return groups.update(groupId, group -> group.rename(name));
        }).orElseThrow(UnauthenticatedException::new).find(groupId).orElseThrow();
    }

    public void delete(UUID memberPublicId, UUID rawGroupId) {
        FavoriteGroupId groupId = new FavoriteGroupId(rawGroupId);
        repository.update(memberPublicId, groups -> {
            requireGroup(groups, groupId);
            return groups.remove(groupId);
        }).orElseThrow(UnauthenticatedException::new);
    }

    public void reorder(UUID memberPublicId, List<UUID> rawGroupIds) {
        if (rawGroupIds == null) {
            throw new InvalidFavoriteGroupOrderException();
        }
        List<FavoriteGroupId> groupIds;
        try {
            groupIds = rawGroupIds.stream().map(FavoriteGroupId::new).toList();
        } catch (RuntimeException exception) {
            throw new InvalidFavoriteGroupOrderException();
        }
        repository.update(memberPublicId, groups -> {
            try {
                return groups.reorder(groupIds);
            } catch (IllegalArgumentException exception) {
                throw new InvalidFavoriteGroupOrderException();
            }
        }).orElseThrow(UnauthenticatedException::new);
    }

    public FavoriteTarget targetOf(String routeId, String stopId, FavoriteDirection direction) {
        try {
            return new FavoriteTarget(routeId, stopId, direction);
        } catch (RuntimeException exception) {
            throw new InvalidFavoriteRequestException();
        }
    }

    private static FavoriteGroupName nameOf(String rawName) {
        try {
            return new FavoriteGroupName(rawName);
        } catch (RuntimeException exception) {
            throw new InvalidFavoriteGroupNameException();
        }
    }

    private static void requireGroup(MemberFavoriteGroups groups, FavoriteGroupId groupId) {
        if (groups.find(groupId).isEmpty()) {
            throw new FavoriteGroupNotFoundException();
        }
    }

    private static void requireUniqueName(MemberFavoriteGroups groups, FavoriteGroupName name, FavoriteGroupId except) {
        boolean duplicate = groups.values().stream()
            .anyMatch(group -> group.name().equals(name) && !group.id().equals(except));
        if (duplicate) {
            throw new FavoriteGroupNameConflictException();
        }
    }
}

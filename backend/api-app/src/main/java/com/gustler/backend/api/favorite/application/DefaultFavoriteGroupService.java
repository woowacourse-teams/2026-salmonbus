package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 회원이 즐겨찾기 기능을 사용할 때 최소 한 개의 그룹을 갖도록 보장한다. */
@Service
public class DefaultFavoriteGroupService {
    static final String DEFAULT_GROUP_NAME = "즐겨찾기";

    private final FavoriteGroupRepository repository;

    public DefaultFavoriteGroupService(FavoriteGroupRepository repository) {
        this.repository = repository;
    }

    public MemberFavoriteGroups ensure(UUID memberPublicId) {
        FavoriteGroup defaultGroup = new FavoriteGroup(
            new FavoriteGroupId(UUID.randomUUID()), new FavoriteGroupName(DEFAULT_GROUP_NAME), List.of());
        return repository.ensureDefault(memberPublicId, defaultGroup)
            .orElseThrow(UnauthenticatedException::new);
    }
}

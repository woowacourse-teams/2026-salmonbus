package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 현재 첫 번째 순서인 대표 즐겨찾기 그룹을 삭제하려 할 때의 공개 실패다. */
public final class RepresentativeFavoriteGroupDeletionException extends ApiException {
    public RepresentativeFavoriteGroupDeletionException() {
        super(ErrorCode.LAST_FAVORITE_GROUP);
    }
}

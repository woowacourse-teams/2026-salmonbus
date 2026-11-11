package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 마지막 즐겨찾기 그룹을 삭제하려 할 때의 공개 실패다. */
public final class LastFavoriteGroupDeletionException extends ApiException {
    public LastFavoriteGroupDeletionException() {
        super(ErrorCode.LAST_FAVORITE_GROUP);
    }
}

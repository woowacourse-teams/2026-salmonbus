package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 회원에게 하나만 남은 즐겨찾기 그룹을 삭제하려 할 때의 공개 실패다. */
public final class LastFavoriteGroupException extends ApiException {
    public LastFavoriteGroupException() {
        super(ErrorCode.LAST_FAVORITE_GROUP);
    }
}

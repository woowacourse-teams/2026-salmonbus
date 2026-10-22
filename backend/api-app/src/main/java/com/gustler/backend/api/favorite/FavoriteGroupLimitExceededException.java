package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 회원 한 명의 즐겨찾기 그룹 한도를 넘겼을 때의 공개 실패다. */
public class FavoriteGroupLimitExceededException extends ApiException {
    public FavoriteGroupLimitExceededException() {
        super(ErrorCode.FAVORITE_GROUP_LIMIT_EXCEEDED);
    }
}

package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 즐겨찾기 그룹 한 개의 즐겨찾기 한도를 넘겼을 때의 공개 실패다. */
public class FavoriteLimitExceededException extends ApiException {
    public FavoriteLimitExceededException() {
        super(ErrorCode.FAVORITE_LIMIT_EXCEEDED);
    }
}

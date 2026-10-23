package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class FavoriteGroupNotFoundException extends ApiException {
    public FavoriteGroupNotFoundException() {
        super(ErrorCode.FAVORITE_GROUP_NOT_FOUND);
    }
}

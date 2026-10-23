package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class FavoriteGroupNameConflictException extends ApiException {
    public FavoriteGroupNameConflictException() {
        super(ErrorCode.FAVORITE_GROUP_NAME_CONFLICT);
    }
}

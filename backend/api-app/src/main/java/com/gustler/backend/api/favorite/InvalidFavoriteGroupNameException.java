package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class InvalidFavoriteGroupNameException extends ApiException {
    public InvalidFavoriteGroupNameException() {
        super(ErrorCode.INVALID_FAVORITE_GROUP_NAME);
    }
}

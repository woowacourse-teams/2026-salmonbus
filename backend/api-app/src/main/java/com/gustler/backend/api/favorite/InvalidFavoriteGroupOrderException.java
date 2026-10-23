package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class InvalidFavoriteGroupOrderException extends ApiException {
    public InvalidFavoriteGroupOrderException() {
        super(ErrorCode.INVALID_FAVORITE_GROUP_ORDER);
    }
}

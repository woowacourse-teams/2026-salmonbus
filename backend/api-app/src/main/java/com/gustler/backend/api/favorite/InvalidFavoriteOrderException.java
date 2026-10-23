package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class InvalidFavoriteOrderException extends ApiException {
    public InvalidFavoriteOrderException() {
        super(ErrorCode.INVALID_FAVORITE_ORDER);
    }
}

package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class InvalidFavoriteRequestException extends ApiException {
    public InvalidFavoriteRequestException() {
        super(ErrorCode.INVALID_REQUEST);
    }
}

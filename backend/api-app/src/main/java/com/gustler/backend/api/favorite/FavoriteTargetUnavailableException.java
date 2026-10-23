package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class FavoriteTargetUnavailableException extends ApiException {
    public FavoriteTargetUnavailableException() {
        super(ErrorCode.FAVORITE_TARGET_UNAVAILABLE);
    }
}

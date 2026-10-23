package com.gustler.backend.api.favorite;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class FavoriteTargetAmbiguousException extends ApiException {
    public FavoriteTargetAmbiguousException() {
        super(ErrorCode.FAVORITE_TARGET_AMBIGUOUS);
    }
}

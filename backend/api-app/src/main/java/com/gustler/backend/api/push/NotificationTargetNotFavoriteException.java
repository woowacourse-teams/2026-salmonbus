package com.gustler.backend.api.push;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class NotificationTargetNotFavoriteException extends ApiException {
    public NotificationTargetNotFavoriteException() {
        super(ErrorCode.NOTIFICATION_TARGET_NOT_FAVORITE);
    }
}

package com.gustler.backend.api.push;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class InvalidNotificationScheduleException extends ApiException {
    public InvalidNotificationScheduleException() {
        super(ErrorCode.INVALID_NOTIFICATION_SCHEDULE);
    }
}

package com.gustler.backend.api.push;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class NotificationSettingNotFoundException extends ApiException {
    public NotificationSettingNotFoundException() {
        super(ErrorCode.NOTIFICATION_SETTING_NOT_FOUND);
    }
}

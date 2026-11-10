package com.gustler.backend.api.push;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public final class InvalidPushDeviceException extends ApiException {
    public InvalidPushDeviceException() {
        super(ErrorCode.INVALID_PUSH_DEVICE);
    }
}

package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public class InvalidReferenceVersionException extends ApiException {
    public InvalidReferenceVersionException() { super(ErrorCode.INVALID_REQUEST); }
}

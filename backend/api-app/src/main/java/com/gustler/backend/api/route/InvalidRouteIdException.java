package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public class InvalidRouteIdException extends ApiException {

    public InvalidRouteIdException() {
        super(ErrorCode.INVALID_ROUTE_ID);
    }
}

package com.gustler.backend.api.route;

import com.gustler.backend.api.http.ApiException;
import com.gustler.backend.api.http.ErrorCode;

public class InvalidRouteIdException extends ApiException {

    public InvalidRouteIdException() {
        super(ErrorCode.INVALID_ROUTE_ID);
    }
}

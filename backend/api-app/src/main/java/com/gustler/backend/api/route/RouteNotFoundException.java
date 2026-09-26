package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public class RouteNotFoundException extends ApiException {

    public RouteNotFoundException() {
        super(ErrorCode.ROUTE_NOT_FOUND);
    }
}

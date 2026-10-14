package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public class RouteGeometryReferenceConflictException extends ApiException {
    public RouteGeometryReferenceConflictException() { super(ErrorCode.REFERENCE_VERSION_CONFLICT); }
}

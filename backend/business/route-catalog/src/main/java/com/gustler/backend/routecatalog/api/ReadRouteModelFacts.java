package com.gustler.backend.routecatalog.api;

import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import java.util.Set;

/** Only trusted model configuration declares consumed roles; no caller-provided facts or grades are accepted. */
public interface ReadRouteModelFacts {
    RouteModelFacts read(Set<RouteModelFacts.Role> consumed, RouteDisplayInput sourceInput, long referenceVersionId);
}

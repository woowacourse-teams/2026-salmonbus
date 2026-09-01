package com.gustler.backend.api.route;

import java.util.regex.Pattern;

public record RouteId(
    String value
) {

    private static final Pattern FORMAT = Pattern.compile("[0-9]{9}");

    public RouteId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidRouteIdException();
        }
    }
}

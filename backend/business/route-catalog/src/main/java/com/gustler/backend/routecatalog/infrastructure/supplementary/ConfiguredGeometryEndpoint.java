package com.gustler.backend.routecatalog.infrastructure.supplementary;

import java.net.URI;

/** Operator configuration only. Credentials and query parameters are never embedded in the endpoint. */
final class ConfiguredGeometryEndpoint {
    private ConfiguredGeometryEndpoint() {}
    static URI parse(String value) {
        try {
            URI uri=URI.create(value);
            if(!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
                throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("INVALID_GEOMETRY_SOURCE_ENDPOINT");}
    }
}

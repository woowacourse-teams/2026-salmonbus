package com.gustler.backend.routecatalog.domain;

import java.time.OffsetDateTime;

/** Connect an acquisition only to the normalized catalog state that it actually produced. */
public interface CatalogSourceBinding {
    boolean bind(long versionId, UpstreamRoute captured, String acquisitionManifestId, OffsetDateTime observedAt);
    CatalogSourceBinding NONE = (versionId,captured,manifest,observedAt) -> false;
}

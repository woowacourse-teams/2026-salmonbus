package com.gustler.backend.routecatalog.domain.geometry;

import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.util.List;

/** Content-addressed immutable acquisition provenance, independent from mutable serving pointers. */
public interface RouteSourceArchive {
    String record(String purpose, List<RouteSourceReceipt> receipts, List<String> reusedManifestIds);
    String bind(long routeId, long referenceVersionId, String acquisitionManifestId,
                RouteDisplayInput input, RouteShapeEvidence evidence);
    ArchivedManifest restore(String manifestId);
    record ArchivedManifest(String manifestId, String purpose, String document,
                            List<RouteSourceReceipt> receipts, List<String> reusedManifestIds) {}
    RouteSourceArchive NONE = new RouteSourceArchive() {
        public String record(String p,List<RouteSourceReceipt> r,List<String> m) { return null; }
        public String bind(long r,long v,String m,RouteDisplayInput i,RouteShapeEvidence e) { return null; }
        public ArchivedManifest restore(String id) { throw new IllegalStateException("Source archive unavailable"); }
    };
}

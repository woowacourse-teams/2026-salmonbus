package com.gustler.backend.forecasting.api.model;

public sealed interface ModelLoadResult {

    record NotConfigured() implements ModelLoadResult { }

    record Rejected(String reason) implements ModelLoadResult { }

    record Activated(long deploymentId) implements ModelLoadResult { }

    record Reloaded(String releaseId) implements ModelLoadResult { }

    record IdentityMismatch(String activeReleaseId, String fileReleaseId) implements ModelLoadResult { }

    record Promoted(String activeReleaseId, String fileReleaseId, long deploymentId) implements ModelLoadResult { }
}

package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;

public interface ModelBundleFiles {
    ModelRelease load();
}

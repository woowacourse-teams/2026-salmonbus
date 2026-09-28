package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;

public interface ModelBundleFiles {
    ModelRelease load();
}

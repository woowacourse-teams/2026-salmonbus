package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.forecasting.configuration.BundleInspectionConfiguration;
import org.springframework.context.annotation.Import;

@Import(BundleInspectionConfiguration.class)
public class BundleInspectionCommandConfiguration {
}

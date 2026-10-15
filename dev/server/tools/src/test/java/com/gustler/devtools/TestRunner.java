package com.gustler.devtools;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;

public final class TestRunner {
    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request =
                LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectPackage("com.gustler.devtools"))
                        .filters(TagFilter.includeTags("linux-root"))
                        .build();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        var output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        if (summary.getTestsFoundCount() == 0
                || summary.getTestsFailedCount() != 0
                || summary.getContainersFailedCount() != 0) {
            System.exit(1);
        }
    }
}

package com.gustler.backend.worker;

import com.gustler.backend.support.TimeSourceRules;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

@AnalyzeClasses(
    packages = "com.gustler.backend",
    importOptions = DoNotIncludeTests.class
)
public class TimeSourceTest {
    @ArchTest
    static final ArchTests CLOCK_USAGE_RULE = ArchTests.in(TimeSourceRules.class);
}

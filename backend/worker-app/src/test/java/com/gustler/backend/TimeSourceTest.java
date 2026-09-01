package com.gustler.backend;

import com.gustler.backend.support.TimeSourceRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

@AnalyzeClasses(
    packages = "com.gustler.backend",
    importOptions = ImportOption.DoNotIncludeTests.class
)
public class TimeSourceTest {

    @ArchTest
    static final ArchTests 시각은_주입받은_Clock_에서만 = ArchTests.in(TimeSourceRules.class);
}

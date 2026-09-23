package com.gustler.backend;

import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@Import({WorkerApplication.class, CollectionRuntimeConfiguration.class})
public class WorkerTestApplication {
}

package com.gustler.backend;

import com.gustler.backend.worker.WorkerApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@Import(WorkerApplication.class)
public class WorkerTestApplication {
}

package com.gustler.backend.api.http.docs;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 API 문서 진입 경로를 제공한다.
 */
@RestController
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ApiDocsContractController {
    @GetMapping("/api/docs")
    public ResponseEntity<Void> entry() {
        return ResponseEntity.status(302)
            .location(URI.create("/api/docs/ui"))
            .cacheControl(CacheControl.noStore())
            .build();
    }
}

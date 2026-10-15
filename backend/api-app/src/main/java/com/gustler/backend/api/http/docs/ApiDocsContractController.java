package com.gustler.backend.api.http.docs;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/api/docs/chat")
    public ResponseEntity<ClassPathResource> chatContract() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
            .body(new ClassPathResource("api-docs/chat-websocket-public.md"));
    }

    @GetMapping("/api/docs/chat/examples")
    public ResponseEntity<ClassPathResource> chatExamples() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ClassPathResource("api-docs/examples/chat-v1.json"));
    }
}

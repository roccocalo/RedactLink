package com.roccocalo.redactlink.service;

import com.roccocalo.redactlink.model.Entity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SanitizationService {

    private final ExtractionService extractionService;
    private final NerService nerService;
    private final RedactionService redactionService;
    private final StringRedisTemplate redisTemplate;

    public void process(String fileId, String objectKey, byte[] fileBytes, String contentType) {
        redisTemplate.opsForValue().set("status:" + fileId, "PROCESSING", Duration.ofHours(24));
        try {
            // Phase 1 — extract plain text
            String extractedText = extractionService.extract(fileBytes);
            log.info("fileId={} extracted {} chars contentType={}", fileId, extractedText.length(), contentType);

            // Phase 2 — detect PII entities
            List<Entity> entities = nerService.analyze(extractedText);
            log.info("fileId={} found {} entities to redact", fileId, entities.size());

            // Phase 3 — redact
            byte[] sanitizedBytes = redactionService.redact(fileBytes, contentType, entities);
            log.info("fileId={} redacted {} → {} bytes", fileId, fileBytes.length, sanitizedBytes.length);

            // Phase 4: upload sanitizedBytes to sanitized-bucket, delete raw, emit SSE COMPLETED
            // Implemented in Phase 4

        } catch (Exception e) {
            log.error("Sanitization failed for fileId={}", fileId, e);
            redisTemplate.opsForValue().set("status:" + fileId, "FAILED", Duration.ofHours(24));
        }
    }
}

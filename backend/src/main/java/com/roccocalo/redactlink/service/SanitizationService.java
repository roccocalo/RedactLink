package com.roccocalo.redactlink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SanitizationService {

    private final ExtractionService extractionService;
    private final StringRedisTemplate redisTemplate;

    // NerService and RedactionService injected here in Phase 3

    public void process(String fileId, String objectKey, byte[] fileBytes, String contentType) {
        redisTemplate.opsForValue().set("status:" + fileId, "PROCESSING", Duration.ofHours(24));
        try {
            String extractedText = extractionService.extract(fileBytes);
            log.info("fileId={} extracted {} chars contentType={}", fileId, extractedText.length(), contentType);

            // Phase 3: NerService.analyze(extractedText) → entities
            // Phase 3: RedactionService.redact(fileBytes, contentType, entities) → sanitizedBytes
            // Phase 4: upload sanitizedBytes to sanitized-bucket, delete raw, emit SSE COMPLETED

        } catch (Exception e) {
            log.error("Sanitization failed for fileId={}", fileId, e);
            redisTemplate.opsForValue().set("status:" + fileId, "FAILED", Duration.ofHours(24));
        }
    }
}

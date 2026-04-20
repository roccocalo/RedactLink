package com.roccocalo.redactlink.service;

import com.roccocalo.redactlink.model.Entity;
import com.roccocalo.redactlink.model.SseEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SanitizationService {

    private final ExtractionService   extractionService;
    private final NerService          nerService;
    private final RedactionService    redactionService;
    private final PresignedUrlService presignedUrlService;
    private final SseService          sseService;
    private final S3Client            s3Client;
    private final StringRedisTemplate redisTemplate;

    @Value("${aws.s3.raw-bucket}")
    private String rawBucket;

    @Value("${aws.s3.sanitized-bucket}")
    private String sanitizedBucket;

    public void process(String fileId, String objectKey, byte[] fileBytes, String contentType) {
        redisTemplate.opsForValue().set("status:" + fileId, "PROCESSING", Duration.ofHours(24));
        try {
            // Phase 1 — extract plain text
            String extractedText = extractionService.extract(fileBytes, contentType);
            log.info("fileId={} extracted {} chars contentType={}", fileId, extractedText.length(), contentType);

            // Phase 2 — detect PII entities
            List<Entity> entities = nerService.analyze(extractedText);
            log.info("fileId={} found {} entities to redact", fileId, entities.size());

            // Phase 3 — redact
            byte[] sanitizedBytes = redactionService.redact(fileBytes, contentType, extractedText, entities);
            log.info("fileId={} redacted {} → {} bytes", fileId, fileBytes.length, sanitizedBytes.length);

            // Phase 4 — upload sanitized file, clean up raw, notify client

            // Upload to sanitized bucket (same object key, different bucket)
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(sanitizedBucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(sanitizedBytes));
            log.info("fileId={} uploaded sanitized file to s3://{}/{}", fileId, sanitizedBucket, objectKey);

            // Delete raw file — no longer needed
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(rawBucket)
                    .key(objectKey)
                    .build());
            log.info("fileId={} deleted raw file from s3://{}/{}", fileId, rawBucket, objectKey);

            // Mark completed in Redis; store sanitized key for dedup/download lookups
            redisTemplate.opsForValue().set("status:"    + fileId, "COMPLETED",  Duration.ofHours(24));
            redisTemplate.opsForValue().set("sanitized:" + fileId, objectKey,     Duration.ofHours(23));

            // Emit SSE event to any waiting client
            String downloadUrl = presignedUrlService.generateDownloadUrl(objectKey);
            sseService.emit(fileId, SseEvent.builder()
                    .status("COMPLETED")
                    .downloadUrl(downloadUrl)
                    .redactedCount(entities.size())
                    .build());
            log.info("fileId={} COMPLETED redactedCount={}", fileId, entities.size());

        } catch (Exception e) {
            log.error("Sanitization failed for fileId={}", fileId, e);
            redisTemplate.opsForValue().set("status:" + fileId, "FAILED", Duration.ofHours(24));
            sseService.emit(fileId, SseEvent.builder()
                    .status("FAILED")
                    .error(e.getMessage())
                    .build());
        }
    }
}

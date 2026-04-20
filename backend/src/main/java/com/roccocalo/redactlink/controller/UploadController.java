package com.roccocalo.redactlink.controller;

import com.roccocalo.redactlink.model.UploadRequest;
import com.roccocalo.redactlink.model.UploadResponse;
import com.roccocalo.redactlink.service.PresignedUrlService;
import com.roccocalo.redactlink.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final PresignedUrlService presignedUrlService;
    private final RateLimitService rateLimitService;
    private final StringRedisTemplate redisTemplate;
    private final S3Client s3Client;

    @Value("${aws.s3.raw-bucket}")
    private String rawBucket;

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "text/plain",
            "text/csv",
            "text/x-log",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    @PostMapping("/request-url")
    public ResponseEntity<UploadResponse> requestUploadUrl(
            @Valid @RequestBody UploadRequest request,
            HttpServletRequest httpRequest) {

        if (!rateLimitService.isAllowed(getClientIp(httpRequest))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        if (!ALLOWED_CONTENT_TYPES.contains(request.getContentType())) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        if (request.getSize() > MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.badRequest().build();
        }

        // SHA-256 dedup: check if this exact file was already processed
        String dedupKey = "sha256:" + request.getSha256();
        String existingFileId = redisTemplate.opsForValue().get(dedupKey);
        if (existingFileId != null) {
            String prevStatus = redisTemplate.opsForValue().get("status:" + existingFileId);

            if ("FAILED".equals(prevStatus)) {
                // Previous attempt failed — delete the orphaned raw file (if still present)
                // and clear stale Redis keys so this upload is treated as fresh.
                String orphanedKey = redisTemplate.opsForValue().get("rawkey:" + existingFileId);
                if (orphanedKey != null) {
                    try {
                        s3Client.deleteObject(DeleteObjectRequest.builder()
                                .bucket(rawBucket).key(orphanedKey).build());
                        log.info("Deleted orphaned raw file for failed fileId={} key={}", existingFileId, orphanedKey);
                    } catch (Exception e) {
                        log.warn("Could not delete orphaned raw file for fileId={}: {}", existingFileId, e.getMessage());
                    }
                }
                redisTemplate.delete(List.of(dedupKey, "status:" + existingFileId, "rawkey:" + existingFileId));
                existingFileId = null; // fall through to new upload path
            } else {
                String sanitizedObjectKey = redisTemplate.opsForValue().get("sanitized:" + existingFileId);
                if (sanitizedObjectKey != null) {
                    // Already fully sanitized — return a fresh download URL, no upload needed
                    return ResponseEntity.ok(UploadResponse.builder()
                            .fileId(existingFileId)
                            .downloadUrl(presignedUrlService.generateDownloadUrl(sanitizedObjectKey))
                            .alreadySanitized(true)
                            .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                            .build());
                }
                // Identical file is still being processed — frontend can watch SSE with the existing fileId
                return ResponseEntity.ok(UploadResponse.builder()
                        .fileId(existingFileId)
                        .alreadySanitized(false)
                        .build());
            }
        }

        // New file — generate a unique object key and a presigned PUT URL
        String fileId = UUID.randomUUID().toString();
        String objectKey = fileId + "/" + request.getFilename();
        String uploadUrl = presignedUrlService.generateUploadUrl(objectKey, request.getContentType());

        // Persist dedup entry, raw object key (for failure cleanup), and initial status
        redisTemplate.opsForValue().set(dedupKey, fileId, Duration.ofHours(23));
        redisTemplate.opsForValue().set("rawkey:" + fileId, objectKey, Duration.ofHours(24));
        redisTemplate.opsForValue().set("status:" + fileId, "PENDING", Duration.ofHours(24));

        return ResponseEntity.ok(UploadResponse.builder()
                .fileId(fileId)
                .uploadUrl(uploadUrl)
                .alreadySanitized(false)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
                .build());
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

package com.roccocalo.redactlink.controller;

import com.roccocalo.redactlink.model.UploadRequest;
import com.roccocalo.redactlink.model.UploadResponse;
import com.roccocalo.redactlink.service.PresignedUrlService;
import com.roccocalo.redactlink.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final PresignedUrlService presignedUrlService;
    private final RateLimitService rateLimitService;
    private final StringRedisTemplate redisTemplate;

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

        // New file — generate a unique object key and a presigned PUT URL
        String fileId = UUID.randomUUID().toString();
        String objectKey = fileId + "/" + request.getFilename();
        String uploadUrl = presignedUrlService.generateUploadUrl(objectKey, request.getContentType());

        // Persist dedup entry and initial status (TTL 24h)
        redisTemplate.opsForValue().set(dedupKey, fileId, Duration.ofHours(23));
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

package com.roccocalo.redactlink.controller;

import com.roccocalo.redactlink.service.PresignedUrlService;
import com.roccocalo.redactlink.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/links")
@RequiredArgsConstructor
public class LinkController {

    private final PresignedUrlService presignedUrlService;
    private final StringRedisTemplate redisTemplate;
    private final RateLimitService    rateLimitService;

    @PostMapping("/{fileId}")
    public ResponseEntity<Map<String, String>> generateLink(
            @PathVariable String fileId,
            HttpServletRequest request) {

        if (!rateLimitService.isAllowed(getClientIp(request))) {
            return ResponseEntity.status(429).build();
        }

        String sanitizedKey = redisTemplate.opsForValue().get("sanitized:" + fileId);
        if (sanitizedKey == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("downloadUrl", presignedUrlService.generateDownloadUrl(sanitizedKey)));
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

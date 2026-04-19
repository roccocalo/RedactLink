package com.roccocalo.redactlink.controller;

import com.roccocalo.redactlink.model.SseEvent;
import com.roccocalo.redactlink.service.PresignedUrlService;
import com.roccocalo.redactlink.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/updates")
@RequiredArgsConstructor
public class StatusController {

    private final SseService           sseService;
    private final StringRedisTemplate  redisTemplate;
    private final PresignedUrlService  presignedUrlService;

    @GetMapping(value = "/{fileId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String fileId) {
        String status = redisTemplate.opsForValue().get("status:" + fileId);

        if ("COMPLETED".equals(status)) {
            return immediateEmit(fileId, completedEvent(fileId));
        }
        if ("FAILED".equals(status)) {
            return immediateEmit(fileId, SseEvent.builder().status("FAILED").error("Processing failed").build());
        }

        // Register emitter, then re-check to close the race window between the
        // status read above and the worker calling sseService.emit().
        SseEmitter emitter = sseService.register(fileId);
        String recheck = redisTemplate.opsForValue().get("status:" + fileId);
        if ("COMPLETED".equals(recheck)) {
            sseService.remove(fileId);
            return immediateEmit(fileId, completedEvent(fileId));
        }
        if ("FAILED".equals(recheck)) {
            sseService.remove(fileId);
            return immediateEmit(fileId, SseEvent.builder().status("FAILED").error("Processing failed").build());
        }

        log.debug("SSE emitter registered for fileId={}", fileId);
        return emitter;
    }

    private SseEmitter immediateEmit(String fileId, SseEvent event) {
        SseEmitter emitter = new SseEmitter(5_000L);
        try {
            emitter.send(SseEmitter.event().data(event));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private SseEvent completedEvent(String fileId) {
        String sanitizedKey = redisTemplate.opsForValue().get("sanitized:" + fileId);
        String downloadUrl  = sanitizedKey != null ? presignedUrlService.generateDownloadUrl(sanitizedKey) : null;
        return SseEvent.builder().status("COMPLETED").downloadUrl(downloadUrl).build();
    }
}

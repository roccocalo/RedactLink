package com.roccocalo.redactlink.service;

import com.roccocalo.redactlink.model.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SseService {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(String fileId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5-min timeout
        emitters.put(fileId, emitter);
        emitter.onCompletion(() -> emitters.remove(fileId));
        emitter.onTimeout(()    -> emitters.remove(fileId));
        return emitter;
    }

    public void remove(String fileId) {
        emitters.remove(fileId);
    }

    public void emit(String fileId, SseEvent event) {
        SseEmitter emitter = emitters.remove(fileId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().data(event));
            emitter.complete();
        } catch (Exception e) {
            log.warn("Failed to emit SSE event for fileId={}: {}", fileId, e.getMessage());
            emitter.completeWithError(e);
        }
    }
}

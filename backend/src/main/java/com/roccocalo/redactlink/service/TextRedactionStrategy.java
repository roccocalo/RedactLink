package com.roccocalo.redactlink.service;

import com.roccocalo.redactlink.model.Entity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class TextRedactionStrategy {

    public byte[] redact(byte[] fileBytes, List<Entity> entities) {
        String text = new String(fileBytes, StandardCharsets.UTF_8);

        // Process in reverse order so replacements don't shift subsequent indices
        List<Entity> sorted = entities.stream()
                .sorted(Comparator.comparingInt(Entity::getStartIndex).reversed())
                .toList();

        for (Entity e : sorted) {
            text = text.substring(0, e.getStartIndex())
                    + "[REDACTED_" + e.getType() + "]"
                    + text.substring(e.getEndIndex());
        }

        text += buildAuditFooter(entities.size());
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String buildAuditFooter(int redactedCount) {
        return "\n# --- REDACTION REPORT ---" +
               "\n# Sanitized-By: ZeroTrust-Engine-v1" +
               "\n# Sanitized-At: " + Instant.now() +
               "\n# Redacted-Count: " + redactedCount + "\n";
    }
}

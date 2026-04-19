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
        List<Entity> sorted = removeOverlaps(entities).stream()
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

    private List<Entity> removeOverlaps(List<Entity> entities) {
        List<Entity> kept = new java.util.ArrayList<>();
        for (Entity candidate : entities.stream()
                .sorted(Comparator.comparingDouble(Entity::getScore).reversed())
                .toList()) {
            boolean overlaps = kept.stream().anyMatch(k ->
                    candidate.getStartIndex() < k.getEndIndex() &&
                    candidate.getEndIndex() > k.getStartIndex());
            if (!overlaps) kept.add(candidate);
        }
        return kept;
    }

    private String buildAuditFooter(int redactedCount) {
        return "\n# --- REDACTION REPORT ---" +
               "\n# Sanitized-By: ZeroTrust-Engine-v1" +
               "\n# Sanitized-At: " + Instant.now() +
               "\n# Redacted-Count: " + redactedCount + "\n";
    }
}

package com.roccocalo.redactlink.service;

import com.roccocalo.redactlink.model.Entity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RedactionService {

    private static final Set<String> TEXT_TYPES = Set.of(
            "text/plain", "text/csv", "text/x-log"
    );

    private final TextRedactionStrategy textStrategy;
    // PdfRedactionStrategy and DocxRedactionStrategy injected in later phase

    public byte[] redact(byte[] fileBytes, String contentType, List<Entity> entities) {
        if (TEXT_TYPES.contains(contentType)) {
            return textStrategy.redact(fileBytes, entities);
        }
        throw new UnsupportedOperationException("No redaction strategy for contentType: " + contentType);
    }
}

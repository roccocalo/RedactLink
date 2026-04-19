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
    private final PdfRedactionStrategy pdfStrategy;

    public byte[] redact(byte[] fileBytes, String contentType, String extractedText, List<Entity> entities) throws Exception {
        if (TEXT_TYPES.contains(contentType)) {
            return textStrategy.redact(fileBytes, entities);
        }
        if ("application/pdf".equals(contentType)) {
            return pdfStrategy.redact(fileBytes, extractedText, entities);
        }
        throw new UnsupportedOperationException("No redaction strategy for contentType: " + contentType);
    }
}

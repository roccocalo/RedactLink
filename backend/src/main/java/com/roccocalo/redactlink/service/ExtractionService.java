package com.roccocalo.redactlink.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class ExtractionService {

    public String extract(byte[] fileBytes, String contentType) throws IOException {
        return switch (contentType) {
            case "text/plain", "text/csv", "text/x-log" -> new String(fileBytes, StandardCharsets.UTF_8);
            case "application/pdf"        -> extractPdfText(fileBytes);
            default                       -> new String(fileBytes, StandardCharsets.UTF_8);
        };
    }

    private String extractPdfText(byte[] fileBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}

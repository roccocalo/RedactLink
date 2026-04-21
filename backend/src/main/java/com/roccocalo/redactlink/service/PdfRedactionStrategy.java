package com.roccocalo.redactlink.service;

import com.roccocalo.redactlink.model.Entity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Component
public class PdfRedactionStrategy {

    public byte[] redact(byte[] fileBytes, String extractedText, List<Entity> entities) throws IOException {
        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            PositionCollector collector = new PositionCollector();
            collector.setSortByPosition(true);
            collector.getText(doc);

            Map<Integer, List<PDRectangle>> rectsByPage = new HashMap<>();
            for (Entity entity : entities) {
                String entityText = extractedText.substring(entity.getStartIndex(), entity.getEndIndex());
                if (entityText.isBlank()) continue;

                String builtText = collector.builtText.toString();
                int idx = builtText.indexOf(entityText);
                while (idx >= 0) {
                    gatherRects(collector.chars, idx, entityText.length(), rectsByPage);
                    idx = builtText.indexOf(entityText, idx + 1);
                }
            }

            for (Map.Entry<Integer, List<PDRectangle>> entry : rectsByPage.entrySet()) {
                PDPage page = doc.getPage(entry.getKey());
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(Color.BLACK);
                    for (PDRectangle r : entry.getValue()) {
                        cs.addRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
                        cs.fill();
                    }
                }
            }

            doc.getDocumentInformation().setCustomMetadataValue("Sanitized-By", "ZeroTrust-Engine-v1");
            doc.getDocumentInformation().setCustomMetadataValue("Sanitized-At", Instant.now().toString());
            doc.getDocumentInformation().setCustomMetadataValue("Redacted-Count", String.valueOf(entities.size()));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void gatherRects(List<CharInfo> chars, int start, int length,
                              Map<Integer, List<PDRectangle>> rectsByPage) {
        int end = Math.min(start + length, chars.size());
        Map<Integer, List<CharInfo>> byPage = new LinkedHashMap<>();
        for (int i = start; i < end; i++) {
            CharInfo ci = chars.get(i);
            if (ci.width() <= 0) continue;
            byPage.computeIfAbsent(ci.pageIndex(), k -> new ArrayList<>()).add(ci);
        }
        for (Map.Entry<Integer, List<CharInfo>> e : byPage.entrySet()) {
            rectsByPage.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                       .addAll(mergeToLineRects(e.getValue()));
        }
    }

    private List<PDRectangle> mergeToLineRects(List<CharInfo> chars) {
        chars.sort(Comparator.comparingDouble((CharInfo c) -> c.baselineY()).reversed()
                             .thenComparingDouble(c -> c.x()));

        List<PDRectangle> rects = new ArrayList<>();
        float baselineY = chars.get(0).baselineY();
        float minX = chars.get(0).x();
        float maxX = chars.get(0).x() + chars.get(0).width();
        float h = chars.get(0).height();

        for (int i = 1; i < chars.size(); i++) {
            CharInfo ci = chars.get(i);
            if (Math.abs(ci.baselineY() - baselineY) < 3f) {
                minX = Math.min(minX, ci.x());
                maxX = Math.max(maxX, ci.x() + ci.width());
                h = Math.max(h, ci.height());
            } else {
                rects.add(toRect(minX, baselineY, maxX, h));
                baselineY = ci.baselineY();
                minX = ci.x();
                maxX = ci.x() + ci.width();
                h = ci.height();
            }
        }
        rects.add(toRect(minX, baselineY, maxX, h));
        return rects;
    }

    private PDRectangle toRect(float minX, float baselineY, float maxX, float h) {
        // baselineY is in PDF space (Y from bottom). Text sits on the baseline and
        // extends upward by ~h (ascenders) and slightly downward by ~0.25h (descenders).
        return new PDRectangle(minX - 1f, baselineY - h * 0.25f - 1f, maxX - minX + 2f, h * 1.25f + 2f);
    }

    private record CharInfo(int pageIndex, float x, float baselineY, float width, float height) {}

    private static class PositionCollector extends PDFTextStripper {
        final StringBuilder builtText = new StringBuilder();
        final List<CharInfo> chars = new ArrayList<>();

        PositionCollector() throws IOException {}

        @Override
        protected void writeString(String str, List<TextPosition> positions) throws IOException {
            // writeString Y coords are in stripper screen-space (Y=0 at top, increases down).
            // PDPageContentStream.addRect expects PDF user-space (Y=0 at bottom, increases up).
            // Convert once here so all stored coords are in PDF space.
            float pageTopY = getCurrentPage().getCropBox().getUpperRightY();
            for (TextPosition tp : positions) {
                String unicode = tp.getUnicode();
                if (unicode == null) continue;
                float pdfY = pageTopY - tp.getYDirAdj();
                for (int i = 0; i < unicode.length(); i++) {
                    chars.add(new CharInfo(
                            getCurrentPageNo() - 1,
                            tp.getXDirAdj(),
                            pdfY,
                            tp.getWidthDirAdj(),
                            tp.getHeightDir()));
                    builtText.append(unicode.charAt(i));
                }
            }
            super.writeString(str, positions);
        }

        // PDFTextStripper emits synthetic word/line separators via these methods rather than
        // writeString(), so they never reach our override above. We mirror them into builtText
        // with zero-width CharInfos so that builtText stays in sync with getText() output —
        // which is what ExtractionService passes to Presidio for entity offset computation.
        @Override
        protected void writeWordSeparator() throws IOException {
            appendSeparator(getWordSeparator());
            super.writeWordSeparator();
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            appendSeparator(getLineSeparator());
            super.writeLineSeparator();
        }

        private void appendSeparator(String sep) {
            int page = Math.max(0, getCurrentPageNo() - 1);
            for (int i = 0; i < sep.length(); i++) {
                chars.add(new CharInfo(page, 0, 0, 0, 0));
                builtText.append(sep.charAt(i));
            }
        }
    }
}

package com.roccocalo.redactlink.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roccocalo.redactlink.model.Entity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NerService {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final double MIN_SCORE = 0.7;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${presidio.analyzer.url}")
    private String presidioUrl;

    public List<Entity> analyze(String text) throws IOException {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("text", text, "language", "en"));

        Request request = new Request.Builder()
                .url(presidioUrl + "/analyze")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Presidio returned HTTP " + response.code());
            }
            Entity[] entities = objectMapper.readValue(response.body().string(), Entity[].class);
            List<Entity> filtered = Arrays.stream(entities)
                    .filter(e -> e.getScore() >= MIN_SCORE)
                    .toList();
            log.info("Presidio found {} entities ({} above threshold)", entities.length, filtered.size());
            return filtered;
        }
    }
}

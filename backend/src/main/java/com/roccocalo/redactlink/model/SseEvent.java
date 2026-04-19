package com.roccocalo.redactlink.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SseEvent {
    private String status;
    private String downloadUrl;
    private int    redactedCount;
    private String error;
}

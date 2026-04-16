package com.roccocalo.redactlink.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class UploadResponse {

    private String fileId;

    /** Present for new uploads — client must PUT the file bytes here. */
    private String uploadUrl;

    /** Present when alreadySanitized=true — client can skip the S3 PUT entirely. */
    private String downloadUrl;

    /** True when an identical file (same SHA-256) was already processed. */
    private boolean alreadySanitized;

    private Instant expiresAt;
}

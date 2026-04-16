package com.roccocalo.redactlink.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class UploadRequest {

    @NotBlank
    private String filename;

    @Positive
    private long size;

    @NotBlank
    private String contentType;

    @NotBlank
    @Pattern(regexp = "^[a-f0-9]{64}$", message = "sha256 must be a 64-char lowercase hex string")
    private String sha256;
}

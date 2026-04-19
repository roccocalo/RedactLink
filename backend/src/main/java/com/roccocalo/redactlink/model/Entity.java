package com.roccocalo.redactlink.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class Entity {

    @JsonProperty("entity_type")
    private String type;

    @JsonProperty("start")
    private int startIndex;

    @JsonProperty("end")
    private int endIndex;

    private double score;
}

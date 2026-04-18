package com.roccocalo.redactlink.service;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
public class ExtractionService {

    private final Tika tika = new Tika();

    public String extract(byte[] fileBytes) throws IOException, TikaException {
        return tika.parseToString(new ByteArrayInputStream(fileBytes));
    }
}

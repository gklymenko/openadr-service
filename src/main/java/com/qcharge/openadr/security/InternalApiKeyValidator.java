package com.qcharge.openadr.security;

import com.qcharge.openadr.exceptions.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalApiKeyValidator {

    private final byte[] configuredKey;

    public InternalApiKeyValidator(@Value("${charge.key:}") String configuredKey) {
        this.configuredKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    public void requireValid(@Nullable String receivedKey) {
        if (configuredKey.length == 0 || receivedKey == null || receivedKey.isBlank()) {
            throw new AccessDeniedException("Access denied.");
        }

        byte[] received = receivedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(configuredKey, received)) {
            throw new AccessDeniedException("Access denied.");
        }
    }
}

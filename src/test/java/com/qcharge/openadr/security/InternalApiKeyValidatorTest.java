package com.qcharge.openadr.security;

import com.qcharge.openadr.exceptions.AccessDeniedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalApiKeyValidatorTest {

    @Test
    void acceptsMatchingKey() {
        InternalApiKeyValidator validator = new InternalApiKeyValidator("expected-key");

        assertDoesNotThrow(() -> validator.requireValid("expected-key"));
    }

    @Test
    void rejectsMissingWrongAndBlankConfiguredKeys() {
        InternalApiKeyValidator validator = new InternalApiKeyValidator("expected-key");

        assertThrows(AccessDeniedException.class, () -> validator.requireValid(null));
        assertThrows(AccessDeniedException.class, () -> validator.requireValid("wrong-key"));
        assertThrows(
                AccessDeniedException.class,
                () -> new InternalApiKeyValidator("").requireValid("any-key")
        );
    }
}

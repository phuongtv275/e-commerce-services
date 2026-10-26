package com.example.notificationservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipientMaskerTests {

    @Test
    @DisplayName("Positive: Mask standard email address")
    void testMaskStandardEmail() {
        assertEquals("j***e@example.com", RecipientMasker.mask("john.doe@example.com"));
        assertEquals("u***r@domain.com", RecipientMasker.mask("user@domain.com"));
        assertEquals("a***c@test.org", RecipientMasker.mask("abc@test.org"));
    }

    @Test
    @DisplayName("Positive: Mask short local part email address")
    void testMaskShortEmail() {
        assertEquals("a***@domain.com", RecipientMasker.mask("ab@domain.com"));
        assertEquals("a***@domain.com", RecipientMasker.mask("a@domain.com"));
    }

    @Test
    @DisplayName("Negative: Return masked fallback on null, blank or invalid email")
    void testMaskInvalidInput() {
        assertEquals("***", RecipientMasker.mask(null));
        assertEquals("***", RecipientMasker.mask(""));
        assertEquals("***", RecipientMasker.mask("   "));
        assertEquals("***", RecipientMasker.mask("invalid-email-without-at"));
        assertEquals("***", RecipientMasker.mask("@domain.com"));
    }
}

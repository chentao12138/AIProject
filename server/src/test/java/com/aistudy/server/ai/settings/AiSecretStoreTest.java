package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI-009 — AES-GCM secret store unit contract.
 */
class AiSecretStoreTest {

    private static final String MASTER_KEY_B64 =
            java.util.Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private AiProviderSecretMapper mapper() {
        return mock(AiProviderSecretMapper.class);
    }

    private AiProperties props(String secretKey) {
        AiProperties p = new AiProperties();
        p.setSecretKey(secretKey);
        return p;
    }

    @Test
    void encryptDecryptRoundTrip() {
        AiProviderSecretMapper mapper = mapper();
        when(mapper.selectByUserSubject("u1")).thenReturn(null).thenAnswer(inv -> {
            // After insert, subsequent select returns stored row via spy-less flow:
            return null;
        });
        AiSecretStore store = new AiSecretStore(mapper, props(MASTER_KEY_B64));
        store.saveApiKey("u1", "sk-step-secret-value");
        // Verify ciphertext never contains plaintext via capture on insert
        org.mockito.ArgumentCaptor<AiProviderSecret> captor =
                org.mockito.ArgumentCaptor.forClass(AiProviderSecret.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        String ciphertext = captor.getValue().getCiphertext();
        assertNotNull(ciphertext);
        assertTrue(!ciphertext.contains("sk-step-secret-value"));
        // decrypt path
        when(mapper.selectByUserSubject("u1")).thenReturn(captor.getValue());
        assertEquals("sk-step-secret-value", store.resolveApiKey("u1"));
    }

    @Test
    void samePlaintextEncryptsToDifferentCiphertext() {
        AiProviderSecretMapper mapper = mapper();
        AiSecretStore store = new AiSecretStore(mapper, props(MASTER_KEY_B64));
        org.mockito.ArgumentCaptor<AiProviderSecret> captor =
                org.mockito.ArgumentCaptor.forClass(AiProviderSecret.class);
        store.saveApiKey("u1", "same-key");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        String first = captor.getAllValues().get(captor.getAllValues().size() - 1).getCiphertext();
        store.saveApiKey("u1", "same-key");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.atLeast(2)).insert(captor.capture());
        // second save may update if row existed; force second encrypt via different subject
        AiProviderSecretMapper mapper2 = mapper();
        AiSecretStore store2 = new AiSecretStore(mapper2, props(MASTER_KEY_B64));
        org.mockito.ArgumentCaptor<AiProviderSecret> captor2 =
                org.mockito.ArgumentCaptor.forClass(AiProviderSecret.class);
        store2.saveApiKey("u2", "same-key");
        org.mockito.Mockito.verify(mapper2).insert(captor2.capture());
        assertNotEquals(first, captor2.getValue().getCiphertext(),
                "unique IV must produce different ciphertext");
    }

    @Test
    void placeholderMaskRejected() {
        AiSecretStore store = new AiSecretStore(mapper(), props(MASTER_KEY_B64));
        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> store.saveApiKey("u1", "********"));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void decryptFailureIsHardErrorWhenSecretExists() {
        AiProviderSecretMapper mapper = mapper();
        AiProviderSecret bad = new AiProviderSecret();
        bad.setUserSubject("u1");
        bad.setCiphertext("not-valid-ciphertext!!!");
        when(mapper.selectByUserSubject("u1")).thenReturn(bad);
        AiSecretStore store = new AiSecretStore(mapper, props(MASTER_KEY_B64));
        assertThrows(IllegalStateException.class, () -> store.resolveApiKey("u1"));
    }

    @Test
    void missingMasterKeyFailsWhenSecretExists() {
        AiProviderSecretMapper mapper = mapper();
        AiProviderSecret row = new AiProviderSecret();
        row.setUserSubject("u1");
        row.setCiphertext(java.util.Base64.getEncoder().encodeToString(new byte[20]));
        when(mapper.selectByUserSubject("u1")).thenReturn(row);
        AiSecretStore store = new AiSecretStore(mapper, props(""));
        // Renders as 503 + AI_NOT_CONFIGURED rather than an anonymous 500, so the
        // UI can tell "this server cannot hold keys" from a crash.
        com.aistudy.server.common.problem.ApiException ex = assertThrows(
                com.aistudy.server.common.problem.ApiException.class, () -> store.resolveApiKey("u1"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ex.status());
        assertEquals(com.aistudy.server.common.problem.ApiErrorCodes.AI_NOT_CONFIGURED, ex.code());
    }

    @Test
    void noSecretReturnsNull() {
        AiProviderSecretMapper mapper = mapper();
        when(mapper.selectByUserSubject("u1")).thenReturn(null);
        AiSecretStore store = new AiSecretStore(mapper, props(MASTER_KEY_B64));
        assertNull(store.resolveApiKey("u1"));
    }

    @Test
    void shortMasterKeyRejected() {
        AiProperties p = new AiProperties();
        p.setSecretKey(java.util.Base64.getEncoder().encodeToString("short".getBytes()));
        assertThrows(IllegalStateException.class, () -> new AiSecretStore(mapper(), p));
    }

    /**
     * A 48-byte key satisfied the old "at least 256 bits" rule and then failed
     * inside Cipher.init on the first save, surfacing as a bare 500 on
     * PUT /api/v1/settings/ai.
     */
    @Test
    void overlongMasterKeyRejectedAtConstruction() {
        AiProperties p = new AiProperties();
        p.setSecretKey(java.util.Base64.getEncoder().encodeToString(new byte[48]));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new AiSecretStore(mapper(), p));
        assertTrue(ex.getMessage().contains("exactly"),
                "must name the accepted key shape, was: " + ex.getMessage());
    }
}

package com.aistudy.server.ai.settings;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI-009 — per-user write-only API key store.
 *
 * <p>DB holds AES-GCM ciphertext only
 * ({@code Base64(IV(12) || ciphertext+tag)}; unique IV per encryption).
 * Master key is {@code aistudy.ai.secret-key} (Base64 decoding to exactly 32
 * bytes, i.e. AES-256) from the environment. Never logs or returns the
 * plaintext key. Missing master key does not break app startup; an
 * unusable one fails at startup rather than at the first save.
 */
@Service
public class AiSecretStore {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BIT = 128;

    private final AiProviderSecretMapper secretMapper;
    private final SecretKeySpec masterKey;

    public AiSecretStore(AiProviderSecretMapper secretMapper, AiProperties properties) {
        this.secretMapper = secretMapper;
        this.masterKey = parseMasterKey(properties.getSecretKey());
    }

    public boolean hasApiKey(String userSubject) {
        if (userSubject == null || userSubject.isBlank()) {
            return false;
        }
        return secretMapper.selectByUserSubject(userSubject) != null;
    }

    @Transactional
    public void saveApiKey(String userSubject, String plaintextKey) {
        requireSubject(userSubject);
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return;
        }
        String value = plaintextKey.trim();
        if (isPlaceholderMask(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "placeholder value is not a valid API key");
        }
        requireMasterKey();
        String ciphertext = encrypt(value);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AiProviderSecret latest = secretMapper.selectByUserSubject(userSubject);
        if (latest == null) {
            AiProviderSecret secret = new AiProviderSecret();
            secret.setUserSubject(userSubject);
            secret.setCiphertext(ciphertext);
            secret.setCreatedAt(now);
            secret.setUpdatedAt(now);
            secretMapper.insert(secret);
        } else {
            latest.setCiphertext(ciphertext);
            latest.setUpdatedAt(now);
            secretMapper.updateById(latest);
        }
    }

    @Transactional
    public void deleteApiKey(String userSubject) {
        requireSubject(userSubject);
        AiProviderSecret latest = secretMapper.selectByUserSubject(userSubject);
        if (latest != null) {
            secretMapper.deleteById(latest.getId());
        }
    }

    /**
     * Resolves plaintext for backend provider calls only.
     * Decrypt failure is NOT treated as "no secret" — callers must not
     * silently fall back to the env key when ciphertext exists but cannot
     * be decrypted.
     *
     * @return plaintext key, or {@code null} only when no secret row exists
     * @throws IllegalStateException when a secret exists but cannot be decrypted
     */
    public String resolveApiKey(String userSubject) {
        requireSubject(userSubject);
        AiProviderSecret latest = secretMapper.selectByUserSubject(userSubject);
        if (latest == null) {
            return null;
        }
        if (latest.getCiphertext() == null || latest.getCiphertext().isBlank()) {
            throw new IllegalStateException("stored AI API key ciphertext is empty");
        }
        requireMasterKey();
        return decrypt(latest.getCiphertext());
    }

    private static void requireSubject(String userSubject) {
        if (userSubject == null || userSubject.isBlank()) {
            throw new IllegalArgumentException("userSubject is required");
        }
    }

    private void requireMasterKey() {
        if (masterKey == null) {
            // Operator configuration gap, not a client error: a stable code lets
            // the UI say "this server cannot store keys" instead of "500".
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCodes.AI_NOT_CONFIGURED,
                    "服务器未配置 AI 密钥存储（AISTUDY_AI_SECRET_KEY），暂时无法保存 API Key。");
        }
    }

    private static boolean isPlaceholderMask(String value) {
        return value.equals("********")
                || value.chars().allMatch(c -> c == '*')
                || value.equalsIgnoreCase("null")
                || value.equalsIgnoreCase("undefined");
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encrypt AI API key", e);
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            if (payload.length <= IV_LENGTH) {
                throw new IllegalStateException("invalid AI API key ciphertext");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to decrypt AI API key", e);
        }
    }

    private static SecretKeySpec parseMasterKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(secretKey.trim());
            // AES accepts 16/24/32-byte keys only. A longer key would pass a
            // "at least 256-bit" check and then fail inside Cipher.init at the
            // first save, surfacing as a 500 on PUT /settings/ai — so the only
            // accepted shape here is exactly AES-256.
            if (keyBytes.length != 32) {
                throw new IllegalStateException("AISTUDY_AI_SECRET_KEY must decode to exactly "
                        + "256 bits for AES-256-GCM (Base64 of 32 bytes); got "
                        + keyBytes.length + " bytes");
            }
            return new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AISTUDY_AI_SECRET_KEY must be Base64", e);
        }
    }
}

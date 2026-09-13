package com.aistudy.server.ai.settings;

/**
 * AI-009 — closed AI provider / preset identifiers for runtime settings.
 */
public final class AiProviderIds {

    public static final String OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";
    public static final String PRESET_STEPMUN = "STEPFUN";
    public static final String PRESET_STEPMUN_BASE_URL = "https://api.stepfun.com/v1";
    public static final String PRESET_STEPMUN_MODEL = "step-3.5-flash";

    private AiProviderIds() {
    }

    public static String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("OPENAI_COMPATIBLE".equals(upper) || "OPENAI-COMPATIBLE".equals(upper)) {
            return OPENAI_COMPATIBLE;
        }
        throw new IllegalArgumentException("unsupported AI provider: " + raw);
    }

    public static String normalizePreset(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (PRESET_STEPMUN.equals(upper)) {
            return PRESET_STEPMUN;
        }
        throw new IllegalArgumentException("unsupported AI preset: " + raw);
    }
}

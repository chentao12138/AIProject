package com.aistudy.server.ai.service;

/**
 * A generation run that failed for a reason the job row can name.
 *
 * <p>Carries a stable {@code errorCode} written to
 * {@code ai_generation_job.error_code} plus a {@code safeMessage} that is
 * already client-safe, so the worker never has to guess between "internal
 * error" and a real diagnosis. Throwing this is how a handler declares "the run
 * failed, write a terminal FAILED row with this code" — as opposed to
 * {@link LeaseLostException}, which means "write nothing".
 */
public class AiJobFailureException extends RuntimeException {

    private final String errorCode;
    private final String safeMessage;

    public AiJobFailureException(String errorCode, String message) {
        super(AIGenerationJobService.sanitizeSafeMessage(new RuntimeException(message)));
        this.errorCode = errorCode;
        this.safeMessage = getMessage();
    }

    public String errorCode() {
        return errorCode;
    }

    /** Client-safe text, already run through the provider-detail redaction. */
    public String safeMessage() {
        return safeMessage;
    }

    /**
     * Raised when the lease this attempt claimed has already been taken by
     * recovery. The run must abandon before calling the provider, and must not
     * write a terminal state: the job is not failed, it is simply no longer
     * ours.
     */
    public static class LeaseLostException extends RuntimeException {

        public LeaseLostException(Long jobId) {
            super("lease for AI generation job " + jobId + " was reclaimed");
        }
    }
}

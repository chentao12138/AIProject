package com.aistudy.server.ai.job;

import com.aistudy.server.ai.entity.AIGenerationJob;
import com.aistudy.server.ai.mapper.AIGenerationJobMapper;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test double for {@link AIGenerationJobMapper} that re-implements each
 * annotated statement's {@code WHERE} clause in memory.
 *
 * <p>It exists so the lease and scope rules are exercised as <em>behaviour</em>
 * (who may write, who is refused) rather than as a mock returning a canned
 * {@code 1}. The predicates are transcribed from the annotations, so a guard
 * that was dropped from a statement shows up here as a row that updates when it
 * should not.
 *
 * <p>It is not a database: it cannot catch a typo in the SQL text itself, which
 * stays covered by the MySQL integration lane.
 */
public class AiGenerationJobTableFake {

    private final Map<Long, AIGenerationJob> rows = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(100);

    /** Every statement the fake executed, for "did it even try to write" asserts. */
    private final List<String> calls = new ArrayList<>();

    public AIGenerationJobMapper mapper() {
        return Mockito.mock(AIGenerationJobMapper.class, this::dispatch);
    }

    public List<String> calls() {
        return calls;
    }

    public AIGenerationJob row(Long id) {
        return rows.get(id);
    }

    /** Seeds a row outside the mapper, e.g. a legacy row another lane inserted. */
    public AIGenerationJob seed(AIGenerationJob job) {
        if (job.getId() == null) {
            job.setId(sequence.incrementAndGet());
        }
        rows.put(job.getId(), job);
        return job;
    }

    private Object dispatch(InvocationOnMock invocation) {
        String name = invocation.getMethod().getName();
        Object[] a = invocation.getArguments();
        calls.add(name);
        return switch (name) {
            case "insert" -> insert((AIGenerationJob) a[0]);
            case "selectById" -> copy(rows.get(a[0]));
            case "selectByIdSpaceAndRequester" -> selectByIdSpaceAndRequester(a);
            case "selectBySpaceAndRequester" -> selectBySpaceAndRequester(a);
            case "countBySpaceAndRequester" -> countBySpaceAndRequester(a);
            case "claimPending" -> claimPending(a);
            case "refreshClaim" -> refreshClaim(a);
            case "updateStatus" -> updateStatus(a);
            case "finishJob" -> finishJob(a);
            case "markTerminalFailure" -> markTerminalFailure(a);
            case "retryFailed" -> retryFailed(a);
            case "requeueStaleProcessing" -> requeueStaleProcessing(a);
            case "selectPendingBatch" -> selectPendingBatch(a);
            case "selectAllAdmin" -> selectAllAdmin(a);
            default -> defaultValue(invocation.getMethod().getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class || type == long.class) {
            return 0;
        }
        if (type == boolean.class) {
            return false;
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }

    // ==================== statements ====================

    private Object insert(AIGenerationJob job) {
        job.setId(sequence.incrementAndGet());
        rows.put(job.getId(), copy(job));
        return 1;
    }

    /** WHERE id = ? AND space_id = ? AND requester = ? */
    private Object selectByIdSpaceAndRequester(Object[] a) {
        Long jobId = (Long) a[0];
        Long spaceId = (Long) a[1];
        String requester = (String) a[2];
        AIGenerationJob row = rows.get(jobId);
        if (row == null || !Objects.equals(row.getSpaceId(), spaceId)
                || !Objects.equals(row.getRequester(), requester)) {
            return null;
        }
        return copy(row);
    }

    /** WHERE space_id = ? AND requester = ? ORDER BY created_at DESC, id DESC LIMIT/OFFSET */
    private Object selectBySpaceAndRequester(Object[] a) {
        Long spaceId = (Long) a[0];
        String requester = (String) a[1];
        int offset = (Integer) a[2];
        int limit = (Integer) a[3];
        List<AIGenerationJob> matched = sorted(spaceId, requester, true);
        return page(matched, offset, limit);
    }

    /** COUNT(*) WHERE space_id = ? AND requester = ? */
    private Object countBySpaceAndRequester(Object[] a) {
        return (long) sorted((Long) a[0], (String) a[1], true).size();
    }

    /**
     * Atomic claim. Mirrors the liveness predicate: a PENDING row that still
     * carries an unexpired claim is not up for grabs.
     */
    private Object claimPending(Object[] a) {
        AIGenerationJob row = rows.get(a[0]);
        String workerId = (String) a[1];
        String token = (String) a[2];
        LocalDateTime now = (LocalDateTime) a[3];
        LocalDateTime staleBefore = (LocalDateTime) a[4];
        if (row == null || !"PENDING".equals(row.getStatus())) {
            return 0;
        }
        boolean claimable = row.getClaimedBy() == null || row.getClaimToken() == null
                || row.getClaimedAt() == null || row.getClaimedAt().isBefore(staleBefore);
        if (!claimable) {
            return 0;
        }
        row.setStatus("PROCESSING");
        row.setProgress(1);
        row.setClaimedBy(workerId);
        row.setClaimedAt(now);
        row.setClaimToken(token);
        if (row.getStartedAt() == null) {
            row.setStartedAt(now);
        }
        row.setUpdatedAt(now);
        return 1;
    }

    /** WHERE id = ? AND claimed_by = ? AND claim_token = ? AND status = 'PROCESSING' */
    private Object refreshClaim(Object[] a) {
        AIGenerationJob row = rows.get(a[0]);
        LocalDateTime now = (LocalDateTime) a[3];
        if (row == null || !"PROCESSING".equals(row.getStatus())
                || !Objects.equals(row.getClaimedBy(), a[1])
                || !Objects.equals(row.getClaimToken(), a[2])) {
            return 0;
        }
        row.setClaimedAt(now);
        row.setUpdatedAt(now);
        return 1;
    }

    /** WHERE id = ? AND status = ? AND claim_token = ? */
    private Object updateStatus(Object[] a) {
        AIGenerationJob row = rows.get(a[0]);
        if (row == null || !Objects.equals(row.getStatus(), a[1])
                || !Objects.equals(row.getClaimToken(), a[7])) {
            return 0;
        }
        row.setStatus((String) a[2]);
        row.setProgress((Integer) a[3]);
        row.setErrorCode((String) a[4]);
        row.setSafeMessage((String) a[5]);
        row.setUpdatedAt((LocalDateTime) a[6]);
        return 1;
    }

    /** WHERE id = ? AND status = ? AND claim_token = ? */
    private Object finishJob(Object[] a) {
        AIGenerationJob row = rows.get(a[0]);
        if (row == null || !Objects.equals(row.getStatus(), a[1])
                || !Objects.equals(row.getClaimToken(), a[8])) {
            return 0;
        }
        row.setStatus((String) a[2]);
        row.setProgress((Integer) a[3]);
        row.setSuccessCount((Integer) a[4]);
        row.setFailureCount((Integer) a[5]);
        row.setErrorCode(null);
        row.setSafeMessage(null);
        row.setFinishedAt((LocalDateTime) a[6]);
        row.setUpdatedAt((LocalDateTime) a[7]);
        return 1;
    }

    /** WHERE id = ? AND status = 'PROCESSING' AND claim_token = ? — status and diagnostics together */
    private Object markTerminalFailure(Object[] a) {
        AIGenerationJob row = rows.get(a[0]);
        if (row == null || !"PROCESSING".equals(row.getStatus())
                || !Objects.equals(row.getClaimToken(), a[8])) {
            return 0;
        }
        row.setStatus("FAILED");
        row.setProgress((Integer) a[1]);
        row.setSuccessCount((Integer) a[2]);
        row.setFailureCount((Integer) a[3]);
        row.setErrorCode((String) a[4]);
        row.setSafeMessage((String) a[5]);
        row.setFinishedAt((LocalDateTime) a[6]);
        row.setUpdatedAt((LocalDateTime) a[7]);
        return 1;
    }

    /** WHERE id = ? AND status = 'FAILED' — lease cleared, per-run counters reset */
    private Object retryFailed(Object[] a) {
        AIGenerationJob row = rows.get(a[0]);
        LocalDateTime now = (LocalDateTime) a[1];
        if (row == null || !"FAILED".equals(row.getStatus())) {
            return 0;
        }
        row.setStatus("PENDING");
        row.setProgress(0);
        row.setRetryCount(row.getRetryCount() == null ? 1 : row.getRetryCount() + 1);
        row.setSuccessCount(0);
        row.setFailureCount(0);
        row.setErrorCode(null);
        row.setSafeMessage(null);
        row.setStartedAt(null);
        row.setFinishedAt(null);
        row.setClaimedBy(null);
        row.setClaimedAt(null);
        row.setClaimToken(null);
        row.setUpdatedAt(now);
        return 1;
    }

    /** WHERE status = 'PROCESSING' AND (claimed_at IS NULL OR claimed_at < ?) */
    private Object requeueStaleProcessing(Object[] a) {
        LocalDateTime now = (LocalDateTime) a[0];
        LocalDateTime staleBefore = (LocalDateTime) a[1];
        int touched = 0;
        for (AIGenerationJob row : rows.values()) {
            if (!"PROCESSING".equals(row.getStatus())) {
                continue;
            }
            if (row.getClaimedAt() != null && !row.getClaimedAt().isBefore(staleBefore)) {
                continue;
            }
            row.setStatus("PENDING");
            row.setClaimedBy(null);
            row.setClaimedAt(null);
            row.setClaimToken(null);
            row.setUpdatedAt(now);
            touched++;
        }
        return touched;
    }

    /** WHERE status = 'PENDING' ORDER BY created_at ASC, id ASC LIMIT ? */
    private Object selectPendingBatch(Object[] a) {
        List<AIGenerationJob> pending = rows.values().stream()
                .filter(row -> "PENDING".equals(row.getStatus()))
                .sorted(Comparator.comparing(AIGenerationJob::getCreatedAt)
                        .thenComparing(AIGenerationJob::getId))
                .toList();
        return page(pending, 0, (Integer) a[0]);
    }

    private Object selectAllAdmin(Object[] a) {
        String status = (String) a[0];
        Long spaceId = (Long) a[1];
        return rows.values().stream()
                .filter(row -> status == null || status.equals(row.getStatus()))
                .filter(row -> spaceId == null || spaceId.equals(row.getSpaceId()))
                .sorted(Comparator.comparing(AIGenerationJob::getCreatedAt).reversed()
                        .thenComparing(AIGenerationJob::getId, Comparator.reverseOrder()))
                .map(AiGenerationJobTableFake::copy)
                .toList();
    }

    // ==================== helpers ====================

    private List<AIGenerationJob> sorted(Long spaceId, String requester, boolean newestFirst) {
        Comparator<AIGenerationJob> byTime =
                Comparator.comparing(AIGenerationJob::getCreatedAt).thenComparing(AIGenerationJob::getId);
        return rows.values().stream()
                .filter(row -> Objects.equals(row.getSpaceId(), spaceId))
                .filter(row -> Objects.equals(row.getRequester(), requester))
                .sorted(newestFirst ? byTime.reversed() : byTime)
                .map(AiGenerationJobTableFake::copy)
                .toList();
    }

    private static List<AIGenerationJob> page(List<AIGenerationJob> all, int offset, int limit) {
        List<AIGenerationJob> out = new ArrayList<>();
        for (int i = offset; i < Math.min(all.size(), offset + Math.max(0, limit)); i++) {
            out.add(all.get(i));
        }
        return out;
    }

    /** Defensive copy: a service mutating a read result must not edit the table. */
    private static AIGenerationJob copy(AIGenerationJob source) {
        if (source == null) {
            return null;
        }
        AIGenerationJob target = new AIGenerationJob();
        target.setId(source.getId());
        target.setJobType(source.getJobType());
        target.setStatus(source.getStatus());
        target.setProgress(source.getProgress());
        target.setRequester(source.getRequester());
        target.setSpaceId(source.getSpaceId());
        target.setSourceId(source.getSourceId());
        target.setRevisionId(source.getRevisionId());
        target.setKnowledgePointId(source.getKnowledgePointId());
        target.setSuccessCount(source.getSuccessCount());
        target.setFailureCount(source.getFailureCount());
        target.setErrorCode(source.getErrorCode());
        target.setSafeMessage(source.getSafeMessage());
        target.setStartedAt(source.getStartedAt());
        target.setFinishedAt(source.getFinishedAt());
        target.setRetryCount(source.getRetryCount());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setClaimedBy(source.getClaimedBy());
        target.setClaimedAt(source.getClaimedAt());
        target.setClaimToken(source.getClaimToken());
        return target;
    }
}

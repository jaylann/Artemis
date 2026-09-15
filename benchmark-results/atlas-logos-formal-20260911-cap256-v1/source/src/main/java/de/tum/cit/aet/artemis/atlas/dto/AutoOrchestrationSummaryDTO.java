package de.tum.cit.aet.artemis.atlas.dto;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * WebSocket payload broadcast after the automatic orchestrator finishes draining a course's
 * accumulated batch. One message per scheduler tick that actually fired a run; subscribers (the
 * instructor's browser) render a toast linking back to the orchestrator audit dialog.
 * <p>
 * {@code exerciseCount} counts the total number of changed learning objects in the batch — exercises
 * <em>and</em> lecture units. The field name is kept for wire compatibility with the existing client
 * ({@code AutoOrchestrationSummary.exerciseCount}). The three counters are derived from the immutable
 * per-object evidence included in {@code objectOutcomes}.
 *
 * @param courseId       the course whose batch was drained
 * @param runId          opaque identifier matching scheduler logs for traceability
 * @param status         terminal status returned by the orchestration service
 * @param exerciseCount  total number of changed learning objects (exercises + lecture units) in the batch
 * @param successCount   number of objects that reached a verified decision
 * @param failureCount   number of failed, partial, or deferred objects
 * @param skippedCount   number of unsupported, unavailable, or blank objects
 * @param objectOutcomes immutable per-object evidence used to derive the counters
 * @param completedAt    wall-clock time the broadcast was generated
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AutoOrchestrationSummaryDTO(long courseId, @NonNull String runId, CompetencyOrchestrationResultDTO.@NonNull Status status, int exerciseCount, int successCount,
        int failureCount, int skippedCount, List<LearningObjectOutcomeDTO> objectOutcomes, @NonNull Instant completedAt) {

    public AutoOrchestrationSummaryDTO {
        objectOutcomes = objectOutcomes == null ? List.of() : List.copyOf(objectOutcomes);
        if (exerciseCount < 0 || successCount < 0 || failureCount < 0 || skippedCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        if (successCount + failureCount + skippedCount != exerciseCount) {
            throw new IllegalArgumentException("successCount + failureCount + skippedCount must equal exerciseCount");
        }
        if (objectOutcomes.size() != exerciseCount) {
            throw new IllegalArgumentException("objectOutcomes must contain exactly one entry per changed learning object");
        }
        if (objectOutcomes.stream().map(outcome -> java.util.Map.entry(outcome.objectType(), outcome.objectId())).distinct().count() != exerciseCount) {
            throw new IllegalArgumentException("objectOutcomes must not contain duplicate learning objects");
        }
        int derivedSuccessCount = (int) objectOutcomes.stream().filter(outcome -> outcome.status() == LearningObjectOutcomeDTO.Status.PROCESSED).count();
        int derivedSkippedCount = (int) objectOutcomes.stream().filter(outcome -> outcome.status() == LearningObjectOutcomeDTO.Status.SKIPPED).count();
        int derivedFailureCount = exerciseCount - derivedSuccessCount - derivedSkippedCount;
        if (successCount != derivedSuccessCount || failureCount != derivedFailureCount || skippedCount != derivedSkippedCount) {
            throw new IllegalArgumentException("counts must match objectOutcomes");
        }
    }

}

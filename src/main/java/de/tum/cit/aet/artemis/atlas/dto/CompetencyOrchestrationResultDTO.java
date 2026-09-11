package de.tum.cit.aet.artemis.atlas.dto;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompetencyOrchestrationResultDTO(@NonNull Status status, @NonNull String summary, List<AppliedActionDTO> appliedActions, @Nullable FailureReason failureReason,
        List<LearningObjectOutcomeDTO> objectOutcomes) {

    public CompetencyOrchestrationResultDTO {
        appliedActions = appliedActions == null ? List.of() : List.copyOf(appliedActions);
        objectOutcomes = objectOutcomes == null ? List.of() : List.copyOf(objectOutcomes);
        if (objectOutcomes.stream().map(outcome -> java.util.Map.entry(outcome.objectType(), outcome.objectId())).distinct().count() != objectOutcomes.size()) {
            throw new IllegalArgumentException("objectOutcomes must not contain duplicate learning objects");
        }
        if (status == Status.SUCCESS && summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank when status is SUCCESS");
        }
        if (status == Status.FAILED && failureReason == null) {
            throw new IllegalArgumentException("failureReason must be set when status is FAILED");
        }
        if (status != Status.FAILED && status != Status.PARTIAL && failureReason != null) {
            throw new IllegalArgumentException("failureReason must be null unless status is FAILED or PARTIAL");
        }
    }

    public enum Status {
        SUCCESS, PARTIAL, FAILED, IN_PROGRESS, NO_OP
    }

    /**
     * Distinguishes the ways a run can fail without forcing the controller to parse a message
     * string. Drives the HTTP status code returned by {@link de.tum.cit.aet.artemis.atlas.web.CompetencyOrchestrationResource}.
     */
    public enum FailureReason {
        /** No {@code ChatClient} bean is configured (Atlas chat disabled or misconfigured) — surfaced as 503. */
        NO_CHAT_CLIENT,
        /** The LLM call itself threw — surfaced as 502. */
        LLM_ERROR,
        /** The shared tool-call budget was exhausted; terminal, not automatically retried. */
        TOOL_CALL_LIMIT_EXCEEDED,
        /**
         * A non-LLM step in the orchestrator failed (content extraction, repository lookup,
         * template rendering, tool-index assembly) — surfaced as 500.
         */
        INTERNAL_ERROR,
        /**
         * The exercise the orchestrator was triggered for is not a course exercise (currently:
         * exam exercises). Mutating competencies for an exam exercise would silently affect the
         * underlying course's competencies, which is never what the instructor wants —
         * surfaced as 422.
         */
        UNSUPPORTED_EXERCISE,
        /** The requested lecture unit is missing, exercise-backed, or otherwise not content-bearing. */
        UNSUPPORTED_LEARNING_OBJECT
    }

    public static CompetencyOrchestrationResultDTO success(@NonNull String summary, List<AppliedActionDTO> appliedActions) {
        return new CompetencyOrchestrationResultDTO(Status.SUCCESS, summary, appliedActions, null, List.of());
    }

    /**
     * The LLM run failed but had already committed at least one mutation; the caller needs the
     * audit trail so the partial change can be reviewed/reverted.
     */
    public static CompetencyOrchestrationResultDTO partial(@NonNull String summary, List<AppliedActionDTO> appliedActions, FailureReason failureReason) {
        return new CompetencyOrchestrationResultDTO(Status.PARTIAL, summary, appliedActions, failureReason, List.of());
    }

    public static CompetencyOrchestrationResultDTO failed(@NonNull String summary, FailureReason failureReason) {
        return new CompetencyOrchestrationResultDTO(Status.FAILED, summary, List.of(), failureReason, List.of());
    }

    public static CompetencyOrchestrationResultDTO inProgress(@NonNull String summary) {
        return new CompetencyOrchestrationResultDTO(Status.IN_PROGRESS, summary, List.of(), null, List.of());
    }

    /**
     * The run completed without anything to do: every claimed exercise resolved to nothing
     * applicable (deleted, exam, or owned by another course). No competencies were touched, so the
     * caller must not report the claimed ids as successfully processed.
     */
    public static CompetencyOrchestrationResultDTO noOp(@NonNull String summary) {
        return new CompetencyOrchestrationResultDTO(Status.NO_OP, summary, List.of(), null, List.of());
    }

    /** Returns this terminal result with immutable per-object evidence attached. */
    public CompetencyOrchestrationResultDTO withObjectOutcomes(List<LearningObjectOutcomeDTO> outcomes) {
        return new CompetencyOrchestrationResultDTO(status, summary, appliedActions, failureReason, outcomes);
    }

}

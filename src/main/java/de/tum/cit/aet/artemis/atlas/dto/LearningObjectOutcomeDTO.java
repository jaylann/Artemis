package de.tum.cit.aet.artemis.atlas.dto;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-learning-object evidence for a manual or automatic orchestration batch.
 *
 * @param objectType    the Artemis learning-object family
 * @param objectId      the stable learning-object identifier
 * @param status        the terminal outcome within this run
 * @param retryEligible whether the object remained untouched and may safely re-enter automatic processing
 * @param detail        bounded diagnostic text for staff and logs
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LearningObjectOutcomeDTO(@NonNull ObjectType objectType, long objectId, @NonNull Status status, boolean retryEligible, @NonNull String detail) {

    private static final int MAX_DETAIL_LENGTH = 500;

    public LearningObjectOutcomeDTO {
        if (objectId <= 0) {
            throw new IllegalArgumentException("objectId must be positive");
        }
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail must contain between 1 and " + MAX_DETAIL_LENGTH + " characters");
        }
        if (retryEligible && status != Status.FAILED && status != Status.DEFERRED) {
            throw new IllegalArgumentException("only failed or deferred objects may be retry eligible");
        }
    }

    public enum ObjectType {
        EXERCISE, LECTURE_UNIT
    }

    public enum Status {
        /** Content entered a completed and verified orchestration decision, including a verified no-op. */
        PROCESSED,
        /** The object was unavailable, unsupported, or contained no usable learning text. */
        SKIPPED,
        /** Processing failed before a verified terminal decision. */
        FAILED,
        /** The shared run committed at least one mutation but did not complete verification. */
        PARTIAL,
        /** Another course run prevented processing; the object remained untouched. */
        DEFERRED
    }
}

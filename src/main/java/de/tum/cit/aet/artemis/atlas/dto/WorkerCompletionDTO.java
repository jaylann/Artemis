package de.tum.cit.aet.artemis.atlas.dto;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Terminal status reported by a stateless Atlas role worker. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record WorkerCompletionDTO(boolean success, @NonNull String message) {

    public WorkerCompletionDTO {
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}

package de.tum.cit.aet.artemis.atlas.dto;

import java.util.List;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result returned synchronously from a role-worker delegation to the main orchestrator. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record WorkerResultDTO(boolean success, @NonNull String message, List<AppliedActionDTO> appliedActions) {

    public WorkerResultDTO {
        appliedActions = appliedActions == null ? List.of() : List.copyOf(appliedActions);
    }
}

package de.tum.cit.aet.artemis.atlas.service;

import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.errorJson;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.toJson;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.atlas.config.AtlasEnabled;
import de.tum.cit.aet.artemis.atlas.dto.OrchestrationCompletionDTO;

/**
 * Terminal tool exposed only to the main autonomous Atlas orchestrator.
 * <p>
 * Completion is request-scoped and intentionally not a persisted plan DTO. The enclosing service
 * maps the terminal state and shared action buffer to the existing public orchestration result.
 */
@Lazy
@Service
@Conditional(AtlasEnabled.class)
public class AtlasOrchestratorTerminalToolService {

    private final ObjectMapper objectMapper;

    public AtlasOrchestratorTerminalToolService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Mark the main orchestration call as terminal. Every successful or no-op run must use this
     * tool after its final reread and verification; returning text without it is an explicit failure.
     *
     * @param verified    whether the final competency index satisfies the batch requirements
     * @param message     concise instructor-facing result message
     * @param toolContext request-scoped orchestration context
     * @return JSON acknowledgement or a structured error
     */
    @Tool(description = "Required final step for the main Atlas orchestrator. Call only after rereading and verifying the final competency index. verified=true means the batch is complete or a deliberate no-op; verified=false reports an incomplete result.")
    public String completeOrchestration(@ToolParam(description = "true only after the final index verification passes") boolean verified,
            @ToolParam(description = "concise instructor-facing completion message") String message, ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return errorJson(objectMapper, "Orchestration context is missing; completeOrchestration cannot be recorded.");
        }
        if (message == null || message.isBlank()) {
            return errorJson(objectMapper, "message is required for completeOrchestration.");
        }
        if (verified && !OrchestratorToolHelpers.hasFreshVerificationRead(toolContext)) {
            return errorJson(objectMapper, "verified=true requires a competency-index refresh after the latest worker delegation.");
        }
        Object value = toolContext.getContext().get(OrchestratorToolContextKeys.ORCHESTRATION_COMPLETION_KEY);
        if (!(value instanceof AtomicReference<?> reference)) {
            return errorJson(objectMapper, "Orchestration completion holder is missing.");
        }
        @SuppressWarnings("unchecked")
        AtomicReference<OrchestrationCompletionDTO> holder = (AtomicReference<OrchestrationCompletionDTO>) reference;
        if (!holder.compareAndSet(null, new OrchestrationCompletionDTO(verified, message.strip()))) {
            return errorJson(objectMapper, "completeOrchestration was already called.");
        }
        return toJson(objectMapper, java.util.Map.of("status", "ok", "verified", verified, "message", message.strip()));
    }
}

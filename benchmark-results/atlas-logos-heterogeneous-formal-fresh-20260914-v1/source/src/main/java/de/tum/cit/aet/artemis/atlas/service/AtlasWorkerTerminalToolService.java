package de.tum.cit.aet.artemis.atlas.service;

import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.errorJson;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.toJson;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.atlas.config.AtlasEnabled;
import de.tum.cit.aet.artemis.atlas.dto.WorkerCompletionDTO;

/**
 * Terminal tool exposed only to Atlas worker calls.
 * <p>
 * A worker must explicitly call this tool after its role writes. The state is request-scoped in
 * Spring AI's tool context and is never persisted; the parent orchestration service converts it to
 * a {@link de.tum.cit.aet.artemis.atlas.dto.WorkerResultDTO} synchronously.
 */
@Lazy
@Service
@Conditional(AtlasEnabled.class)
public class AtlasWorkerTerminalToolService {

    private final ObjectMapper objectMapper;

    public AtlasWorkerTerminalToolService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Mark the worker call as terminal. Missing or blank messages are rejected so a worker cannot
     * accidentally hide a failed semantic batch behind an empty completion.
     *
     * @param success     whether the requested role work completed
     * @param message     concise result or failure explanation
     * @param toolContext request-scoped worker context
     * @return JSON acknowledgement or a structured error
     */
    @Tool(description = "Required final step for every worker task. Report success=true only when the requested semantic batch is complete; otherwise report success=false with the reason.")
    public String completeWorkerTask(@ToolParam(description = "true when the worker completed its requested task") boolean success,
            @ToolParam(description = "concise completion or failure message") String message, ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return errorJson(objectMapper, "Worker context is missing; completeWorkerTask cannot be recorded.");
        }
        long terminalSequence = OrchestratorToolHelpers.markWorkerTerminalActivity(toolContext);
        if (terminalSequence < 0) {
            return errorJson(objectMapper, "completeWorkerTask is invalid after an earlier terminal decision or later worker activity.");
        }
        if (message == null || message.isBlank()) {
            return errorJson(objectMapper, "message is required for completeWorkerTask.");
        }
        if (success && !hasWorkerEvidence(toolContext)) {
            return errorJson(objectMapper, "success=true requires at least one course-scoped read or applied action.");
        }
        Object value = toolContext.getContext().get(OrchestratorToolContextKeys.WORKER_COMPLETION_KEY);
        if (!(value instanceof AtomicReference<?> reference)) {
            return errorJson(objectMapper, "Worker completion holder is missing.");
        }
        @SuppressWarnings("unchecked")
        AtomicReference<WorkerCompletionDTO> holder = (AtomicReference<WorkerCompletionDTO>) reference;
        if (!holder.compareAndSet(null, new WorkerCompletionDTO(success, message.strip()))) {
            return errorJson(objectMapper, "completeWorkerTask was already called.");
        }
        if (!OrchestratorToolHelpers.acceptWorkerCompletion(toolContext, terminalSequence)) {
            holder.set(null);
            return errorJson(objectMapper, "completeWorkerTask could not be accepted because worker activity advanced concurrently.");
        }
        return toJson(objectMapper, java.util.Map.of("status", "ok", "success", success, "message", message.strip()));
    }

    private static boolean hasWorkerEvidence(ToolContext toolContext) {
        Object readValue = toolContext.getContext().get(OrchestratorToolContextKeys.WORKER_READ_COUNT_KEY);
        if (readValue instanceof AtomicInteger reads && reads.get() > 0) {
            return true;
        }
        Object startValue = toolContext.getContext().get(OrchestratorToolContextKeys.WORKER_ACTION_START_KEY);
        OrchestratorToolContextKeys.AppliedActionsBuffer buffer = OrchestratorToolHelpers.appliedActionsBufferFromContext(toolContext);
        if (!(startValue instanceof Number start) || buffer == null) {
            return false;
        }
        synchronized (buffer.actions()) {
            return buffer.actions().size() > start.intValue();
        }
    }
}

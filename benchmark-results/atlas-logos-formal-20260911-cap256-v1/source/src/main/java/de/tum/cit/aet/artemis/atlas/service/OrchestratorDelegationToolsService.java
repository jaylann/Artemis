package de.tum.cit.aet.artemis.atlas.service;

import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolContextKeys.APPLIED_ACTIONS_KEY;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolContextKeys.COURSE_ID_KEY;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolContextKeys.LEARNING_OBJECT_ID_KEY;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.admin.domain.LLMServiceType;
import de.tum.cit.aet.artemis.admin.service.LLMTokenUsageService;
import de.tum.cit.aet.artemis.atlas.config.AtlasEnabled;
import de.tum.cit.aet.artemis.atlas.config.AtlasOrchestratorProperties;
import de.tum.cit.aet.artemis.atlas.config.AtlasToolSurface;
import de.tum.cit.aet.artemis.atlas.dto.AppliedActionDTO;
import de.tum.cit.aet.artemis.atlas.dto.WorkerCompletionDTO;
import de.tum.cit.aet.artemis.atlas.dto.WorkerResultDTO;
import de.tum.cit.aet.artemis.core.security.SecurityUtils;

/** Main-agent tools that synchronously spawn stateless semantic-batch role workers. */
@Lazy
@Service
@Conditional(AtlasEnabled.class)
public class OrchestratorDelegationToolsService {

    private static final String PIPELINE_ID = "ATLAS_ORCHESTRATION";

    private final AtlasPromptTemplateService templateService;

    private final AtlasAgentDelegationService delegationService;

    private final ObjectMapper objectMapper;

    private final ToolCallbackProvider readTools;

    private final ToolCallbackProvider creatorTools;

    private final ToolCallbackProvider assignerTools;

    private final ToolCallbackProvider editorTools;

    private final ToolCallbackProvider terminalTools;

    private final String workerModel;

    private final String workerReasoningEffort;

    private final LLMTokenUsageService llmTokenUsageService;

    private final UserRepository userRepository;

    public OrchestratorDelegationToolsService(AtlasPromptTemplateService templateService, AtlasAgentDelegationService delegationService, ObjectMapper objectMapper,
            @Qualifier("orchestratorReadToolCallbackProvider") AtlasToolSurface readTools, @Qualifier("creatorToolCallbackProvider") AtlasToolSurface creatorTools,
            @Qualifier("assignerToolCallbackProvider") AtlasToolSurface assignerTools, @Qualifier("editorToolCallbackProvider") AtlasToolSurface editorTools,
            @Qualifier("workerTerminalToolCallbackProvider") AtlasToolSurface terminalTools, AtlasOrchestratorProperties properties, LLMTokenUsageService llmTokenUsageService,
            UserRepository userRepository) {
        this.templateService = templateService;
        this.delegationService = delegationService;
        this.objectMapper = objectMapper;
        this.readTools = readTools.provider();
        this.creatorTools = creatorTools.provider();
        this.assignerTools = assignerTools.provider();
        this.editorTools = editorTools.provider();
        this.terminalTools = terminalTools.provider();
        this.workerModel = properties.workerModel();
        this.workerReasoningEffort = properties.workerReasoningEffort();
        this.llmTokenUsageService = llmTokenUsageService;
        this.userRepository = userRepository;
    }

    @Tool(description = "Spawn the Creator worker synchronously for one semantic batch of competency creations. The result contains its success, message, and applied actions.")
    public String delegateToCreator(@ToolParam(description = "complete semantic batch for the Creator worker") String task, ToolContext context) {
        return delegate("prompts/atlas/orchestrator_creator_worker.st", task, creatorTools, context);
    }

    @Tool(description = "Spawn the Assigner worker synchronously for one semantic batch of exercise or lecture-unit link changes.")
    public String delegateToAssigner(@ToolParam(description = "complete semantic batch for the Assigner worker") String task, ToolContext context) {
        return delegate("prompts/atlas/orchestrator_assigner_worker.st", task, assignerTools, context);
    }

    @Tool(description = "Spawn the Editor worker synchronously for one semantic batch of competency field edits or safe deletions.")
    public String delegateToEditor(@ToolParam(description = "complete semantic batch for the Editor worker") String task, ToolContext context) {
        return delegate("prompts/atlas/orchestrator_editor_worker.st", task, editorTools, context);
    }

    private String delegate(String promptPath, String task, ToolCallbackProvider roleTools, ToolContext parentContext) {
        if (task == null || task.isBlank()) {
            return serialize(new WorkerResultDTO(false, "Worker task must not be blank.", List.of()));
        }
        Long courseId = contextLong(parentContext, COURSE_ID_KEY);
        OrchestratorToolContextKeys.AppliedActionsBuffer buffer = OrchestratorToolHelpers.appliedActionsBufferFromContext(parentContext);
        if (courseId == null || buffer == null) {
            return serialize(new WorkerResultDTO(false, "Worker delegation is missing course or audit context.", List.of()));
        }
        AtlasToolCallBudget budget;
        try {
            budget = parentContext == null || parentContext.getContext() == null ? null : AtlasToolCallBudget.existingBudget(parentContext.getContext());
        }
        catch (IllegalStateException ex) {
            return serialize(new WorkerResultDTO(false, "Worker delegation has an invalid tool-call budget.", List.of()));
        }
        if (budget == null) {
            return serialize(new WorkerResultDTO(false, "Worker delegation is missing tool-call budget.", List.of()));
        }

        int actionStart;
        synchronized (buffer.actions()) {
            actionStart = buffer.actions().size();
        }
        AtomicReference<WorkerCompletionDTO> completion = OrchestratorToolContextKeys.newWorkerCompletionHolder();
        Map<String, Object> workerContext = new HashMap<>();
        workerContext.put(COURSE_ID_KEY, courseId);
        workerContext.put("atlasBudgetWorker", true);
        workerContext.put(APPLIED_ACTIONS_KEY, buffer);
        workerContext.put(AtlasToolCallBudget.CONTEXT_KEY, budget);
        workerContext.put(OrchestratorToolContextKeys.WORKER_COMPLETION_KEY, completion);
        workerContext.put(OrchestratorToolContextKeys.WORKER_READ_COUNT_KEY, new AtomicInteger());
        workerContext.put(OrchestratorToolContextKeys.WORKER_ACTION_START_KEY, actionStart);
        workerContext.put(OrchestratorToolContextKeys.WORKER_ACTIVITY_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        workerContext.put(OrchestratorToolContextKeys.WORKER_TERMINAL_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        workerContext.put(OrchestratorToolContextKeys.WORKER_TERMINAL_INVALIDATED_KEY, OrchestratorToolContextKeys.newWorkerTerminalInvalidatedHolder());
        workerContext.put(OrchestratorToolContextKeys.WORKER_STATE_LOCK_KEY, new Object());
        Long learningObjectId = contextLong(parentContext, LEARNING_OBJECT_ID_KEY);
        if (learningObjectId != null) {
            workerContext.put(LEARNING_OBJECT_ID_KEY, learningObjectId);
        }

        try {
            String systemPrompt = templateService.render(promptPath, Map.of("task", task));
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().deploymentName(workerModel).reasoningEffort(workerReasoningEffort);
            ChatResponse response = delegationService.delegateOrchestratorRound(systemPrompt, "Execute the supplied task, then call completeWorkerTask.", options, workerContext,
                    readTools, roleTools, terminalTools);
            trackUsage(response, courseId, learningObjectId);
            AtlasToolCallBudget.checkResponse(response);
        }
        catch (Exception ex) {
            return serialize(new WorkerResultDTO(false, "Worker execution failed: " + Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName()),
                    actionSlice(buffer, actionStart)));
        }
        finally {
            OrchestratorToolHelpers.markDelegation(parentContext);
        }

        WorkerCompletionDTO terminal = completion.get();
        if (terminal == null) {
            String message = OrchestratorToolHelpers.workerCompletionInvalidated(workerContext) ? "Worker completion was invalidated by tool activity after completeWorkerTask."
                    : "Worker returned without calling completeWorkerTask.";
            return serialize(new WorkerResultDTO(false, message, actionSlice(buffer, actionStart)));
        }
        return serialize(new WorkerResultDTO(terminal.success(), terminal.message(), actionSlice(buffer, actionStart)));
    }

    private List<AppliedActionDTO> actionSlice(OrchestratorToolContextKeys.AppliedActionsBuffer buffer, int start) {
        synchronized (buffer.actions()) {
            int safeStart = Math.min(start, buffer.actions().size());
            return new ArrayList<>(buffer.actions().subList(safeStart, buffer.actions().size()));
        }
    }

    private void trackUsage(ChatResponse response, long courseId, Long learningObjectId) {
        Long userId = SecurityUtils.getCurrentUserLogin().flatMap(userRepository::findIdByLogin).orElse(null);
        llmTokenUsageService.trackChatResponseTokenUsage(response, LLMServiceType.ATLAS, PIPELINE_ID, builder -> {
            builder.withCourse(courseId).withUser(userId);
            if (learningObjectId != null) {
                return builder.withExercise(learningObjectId);
            }
            return builder;
        });
    }

    private static Long contextLong(ToolContext context, String key) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String serialize(WorkerResultDTO result) {
        try {
            return objectMapper.writeValueAsString(result);
        }
        catch (JsonProcessingException ex) {
            return "{\"success\":false,\"message\":\"Failed to serialize worker result.\"}";
        }
    }
}

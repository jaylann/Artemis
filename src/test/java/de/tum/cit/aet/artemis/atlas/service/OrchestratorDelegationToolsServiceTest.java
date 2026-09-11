package de.tum.cit.aet.artemis.atlas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.admin.service.LLMTokenUsageService;
import de.tum.cit.aet.artemis.atlas.config.AtlasOrchestratorProperties;
import de.tum.cit.aet.artemis.atlas.config.AtlasToolSurface;
import de.tum.cit.aet.artemis.atlas.dto.AppliedActionDTO;
import de.tum.cit.aet.artemis.atlas.dto.WorkerCompletionDTO;

class OrchestratorDelegationToolsServiceTest {

    private AtlasAgentDelegationService delegationService;

    private OrchestratorDelegationToolsService service;

    @BeforeEach
    void setUp() {
        AtlasPromptTemplateService templates = mock(AtlasPromptTemplateService.class);
        when(templates.render(anyString(), anyMap())).thenReturn("worker prompt");
        delegationService = mock(AtlasAgentDelegationService.class);
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        AtlasToolSurface surface = new AtlasToolSurface(provider);
        AtlasOrchestratorProperties properties = new AtlasOrchestratorProperties("main", 1.0, "xhigh", "worker", "high", 1800, 10, 30000, 10);
        service = new OrchestratorDelegationToolsService(templates, delegationService, new ObjectMapper(), surface, surface, surface, surface, surface, properties,
                mock(LLMTokenUsageService.class), mock(UserRepository.class));
    }

    @Test
    void nestedExceptionBecomesStructuredWorkerFailure() {
        when(delegationService.delegateOrchestratorRound(anyString(), anyString(), any(OpenAiChatOptions.Builder.class), anyMap(), any(ToolCallbackProvider.class),
                any(ToolCallbackProvider.class), any(ToolCallbackProvider.class))).thenThrow(new RuntimeException("provider unavailable"));

        String result = service.delegateToCreator("Create sorting competency", context());

        assertThat(result).contains("\"success\":false").contains("provider unavailable");
    }

    @Test
    void completedWorkerReturnsItsAppliedActionSlice() {
        ToolContext parentContext = context();
        AtlasToolCallBudget parentBudget = (AtlasToolCallBudget) parentContext.getContext().get(AtlasToolCallBudget.CONTEXT_KEY);
        when(delegationService.delegateOrchestratorRound(anyString(), anyString(), any(OpenAiChatOptions.Builder.class), anyMap(), any(ToolCallbackProvider.class),
                any(ToolCallbackProvider.class), any(ToolCallbackProvider.class))).thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> workerContext = invocation.getArgument(3);
                    assertThat(workerContext.get(AtlasToolCallBudget.CONTEXT_KEY)).isSameAs(parentBudget);
                    @SuppressWarnings("unchecked")
                    java.util.concurrent.atomic.AtomicReference<WorkerCompletionDTO> holder = (java.util.concurrent.atomic.AtomicReference<WorkerCompletionDTO>) workerContext
                            .get(OrchestratorToolContextKeys.WORKER_COMPLETION_KEY);
                    OrchestratorToolContextKeys.AppliedActionsBuffer buffer = (OrchestratorToolContextKeys.AppliedActionsBuffer) workerContext
                            .get(OrchestratorToolContextKeys.APPLIED_ACTIONS_KEY);
                    buffer.actions().add(AppliedActionDTO.create(12L, "Sorting", "Created", "Required by changed exercise"));
                    holder.set(new WorkerCompletionDTO(true, "Created competency 12."));
                    return mock(ChatResponse.class);
                });

        String result = service.delegateToCreator("Create sorting competency", parentContext);

        assertThat(result).contains("\"success\":true").contains("Created competency 12").contains("\"competencyId\":12");
    }

    @Test
    void missingParentBudgetFailsClosedBeforeWorkerInvocation() {
        Map<String, Object> values = new HashMap<>(context().getContext());
        values.remove(AtlasToolCallBudget.CONTEXT_KEY);
        ToolContext context = new ToolContext(values);

        String result = service.delegateToCreator("Create sorting competency", context);

        assertThat(result).contains("\"success\":false").contains("missing tool-call budget");
    }

    private static ToolContext context() {
        Map<String, Object> context = new HashMap<>();
        context.put(OrchestratorToolContextKeys.COURSE_ID_KEY, 42L);
        context.put(OrchestratorToolContextKeys.APPLIED_ACTIONS_KEY, new OrchestratorToolContextKeys.AppliedActionsBuffer(Collections.synchronizedList(new ArrayList<>())));
        context.put(AtlasToolCallBudget.CONTEXT_KEY, new AtlasToolCallBudget());
        return new ToolContext(context);
    }
}

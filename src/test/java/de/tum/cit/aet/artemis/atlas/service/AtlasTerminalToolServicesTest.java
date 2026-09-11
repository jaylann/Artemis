package de.tum.cit.aet.artemis.atlas.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.atlas.dto.OrchestrationCompletionDTO;
import de.tum.cit.aet.artemis.atlas.dto.WorkerCompletionDTO;

class AtlasTerminalToolServicesTest {

    @Test
    void workerCompletionRequiresHolderAndCanOnlyBeSetOnce() {
        AtlasWorkerTerminalToolService service = new AtlasWorkerTerminalToolService(new ObjectMapper());
        AtomicReference<WorkerCompletionDTO> holder = OrchestratorToolContextKeys.newWorkerCompletionHolder();
        Map<String, Object> context = new HashMap<>();
        context.put(OrchestratorToolContextKeys.WORKER_COMPLETION_KEY, holder);
        context.put(OrchestratorToolContextKeys.WORKER_READ_COUNT_KEY, new AtomicInteger(1));

        assertThat(service.completeWorkerTask(true, "Created competencies 12 and 13.", new ToolContext(context))).contains("\"success\":true");
        assertThat(holder.get()).isEqualTo(new WorkerCompletionDTO(true, "Created competencies 12 and 13."));
        assertThat(service.completeWorkerTask(false, "duplicate", new ToolContext(context))).contains("already called");
    }

    @Test
    void workerCompletionIsInvalidatedByLaterRead() {
        AtlasWorkerTerminalToolService service = new AtlasWorkerTerminalToolService(new ObjectMapper());
        AtomicReference<WorkerCompletionDTO> holder = OrchestratorToolContextKeys.newWorkerCompletionHolder();
        Map<String, Object> context = new HashMap<>();
        context.put(OrchestratorToolContextKeys.COURSE_ID_KEY, 42L);
        context.put(OrchestratorToolContextKeys.WORKER_COMPLETION_KEY, holder);
        context.put(OrchestratorToolContextKeys.WORKER_READ_COUNT_KEY, new AtomicInteger(1));
        context.put(OrchestratorToolContextKeys.WORKER_ACTIVITY_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        context.put(OrchestratorToolContextKeys.WORKER_TERMINAL_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        context.put(OrchestratorToolContextKeys.WORKER_TERMINAL_INVALIDATED_KEY, OrchestratorToolContextKeys.newWorkerTerminalInvalidatedHolder());
        ToolContext toolContext = new ToolContext(context);

        assertThat(service.completeWorkerTask(true, "done", toolContext)).contains("\"success\":true");
        OrchestratorToolHelpers.courseIdFromContext(toolContext);
        OrchestratorToolHelpers.markWorkerRead(toolContext);

        assertThat(holder.get()).isNull();
        assertThat(service.completeWorkerTask(true, "stale", toolContext)).contains("invalid after");
    }

    @Test
    void mainCompletionStoresVerifiedDecision() {
        AtlasOrchestratorTerminalToolService service = new AtlasOrchestratorTerminalToolService(new ObjectMapper());
        AtomicReference<OrchestrationCompletionDTO> holder = OrchestratorToolContextKeys.newOrchestrationCompletionHolder();
        Map<String, Object> context = new HashMap<>();
        context.put(OrchestratorToolContextKeys.ORCHESTRATION_COMPLETION_KEY, holder);

        assertThat(service.completeOrchestration(false, "One link remains unresolved.", new ToolContext(context))).contains("\"verified\":false");
        assertThat(holder.get()).isEqualTo(new OrchestrationCompletionDTO(false, "One link remains unresolved."));
    }

    @Test
    void verifiedMainCompletionRequiresFreshIndexRead() {
        AtlasOrchestratorTerminalToolService service = new AtlasOrchestratorTerminalToolService(new ObjectMapper());
        Map<String, Object> context = new HashMap<>();
        context.put(OrchestratorToolContextKeys.ORCHESTRATION_COMPLETION_KEY, OrchestratorToolContextKeys.newOrchestrationCompletionHolder());
        context.put(OrchestratorToolContextKeys.LAST_INDEX_READ_SEQUENCE_KEY, new java.util.concurrent.atomic.AtomicLong(1));
        context.put(OrchestratorToolContextKeys.LAST_DELEGATION_SEQUENCE_KEY, new java.util.concurrent.atomic.AtomicLong(2));

        assertThat(service.completeOrchestration(true, "done", new ToolContext(context))).contains("requires a competency-index refresh");
    }
}

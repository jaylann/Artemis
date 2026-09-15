package de.tum.cit.aet.artemis.atlas.config;

import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import de.tum.cit.aet.artemis.atlas.dto.CompetencyOrchestrationResultDTO;
import de.tum.cit.aet.artemis.atlas.service.AtlasToolCallBudget;
import de.tum.cit.aet.artemis.core.benchmark.AtlasLogosBenchmark;

/** Narrow phase boundaries around Atlas orchestration, delegation, flavor stripping, and tools. */
@Aspect
@Component
@Profile(AtlasLogosBenchmark.PROFILE)
public final class AtlasLogosBenchmarkAspect {

    private final AtlasLogosBenchmark.Telemetry telemetry;

    /** Creates the aspect. */
    public AtlasLogosBenchmarkAspect(AtlasLogosBenchmark.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * Owns the invocation terminal event for a run or batch.
     *
     * @param joinPoint unchanged application call
     * @return original application result
     */
    @Around("execution(* de.tum.cit.aet.artemis.atlas.service.CompetencyOrchestrationService.run(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.CompetencyOrchestrationService.runBatch(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.CompetencyOrchestrationService.runWithQueuedFlush(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.CompetencyOrchestrationService.runLectureUnitWithQueuedFlush(..))")
    public Object orchestration(ProceedingJoinPoint joinPoint) throws Throwable {
        Long courseId = joinPoint.getArgs().length > 1 && joinPoint.getArgs()[0] instanceof Number number ? number.longValue() : null;
        AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(courseId, joinPoint.getSignature().getName());
        AtlasLogosBenchmark.Telemetry.Phase phase = telemetry.phase("orchestration");
        try {
            Object result = joinPoint.proceed();
            if (result instanceof CompetencyOrchestrationResultDTO outcome) {
                telemetry.details(Map.of("applicationStatus", outcome.status().name(), "failureReason", outcome.failureReason() == null ? "NONE" : outcome.failureReason().name()));
            }
            scope.complete(terminalStatus(result), false);
            return result;
        }
        catch (Throwable ex) {
            scope.complete("failure", ex instanceof InterruptedException || Thread.currentThread().isInterrupted());
            throw ex;
        }
        finally {
            phase.close();
            scope.close();
        }
    }

    /**
     * Marks the shared delegation boundary.
     *
     * @param joinPoint unchanged application call
     * @return original application result
     */
    @Around("execution(* de.tum.cit.aet.artemis.atlas.service.OrchestratorDelegationToolsService.delegate*(..))")
    public Object worker(ProceedingJoinPoint joinPoint) throws Throwable {
        return phase(joinPoint, "worker");
    }

    /**
     * Marks the LLM flavor-strip boundary.
     *
     * @param joinPoint unchanged application call
     * @return original application result
     */
    @Around("execution(* de.tum.cit.aet.artemis.atlas.service.ContentExtractionService.extractContent(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.ContentExtractionService.stripFlavorText(..))")
    public Object flavorStrip(ProceedingJoinPoint joinPoint) throws Throwable {
        return phase(joinPoint, "flavor_strip");
    }

    /**
     * Marks Atlas tool callbacks without intercepting unrelated Artemis services.
     *
     * @param joinPoint unchanged application call
     * @return original application result
     */
    @Around("execution(* de.tum.cit.aet.artemis.atlas.service.OrchestratorPlanningToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.OrchestratorReadToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.AtlasAgentToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.AssignerToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.CompetencyExpertToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.CompetencyMappingToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.CreatorToolsService.*(..)) || " + "execution(* de.tum.cit.aet.artemis.atlas.service.EditorToolsService.*(..)) || "
            + "execution(* de.tum.cit.aet.artemis.atlas.service.ExerciseMappingToolsService.*(..))")
    public Object tool(ProceedingJoinPoint joinPoint) throws Throwable {
        return phase(joinPoint, "tool");
    }

    /**
     * Reads the same shared budget after a parent or worker round, without changing its behavior.
     *
     * @param joinPoint unchanged delegation call
     * @return original delegation response
     */
    @Around("execution(* de.tum.cit.aet.artemis.atlas.service.AtlasAgentDelegationService.delegateOrchestratorRound(..))")
    public Object budget(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        }
        finally {
            if (joinPoint.getArgs()[3] instanceof Map<?, ?> context && context.get(AtlasToolCallBudget.CONTEXT_KEY) instanceof AtlasToolCallBudget budget) {
                telemetry.details(
                        Map.of("toolCallCount", budget.calls(), "toolActivity", budget.activity(), "workBlocked", budget.workBlocked(), "hardLimitReached", budget.exhausted()));
            }
        }
    }

    private Object phase(ProceedingJoinPoint joinPoint, String name) throws Throwable {
        AtlasLogosBenchmark.Telemetry.Phase phase = telemetry.phase(name);
        try {
            return joinPoint.proceed();
        }
        finally {
            phase.close();
        }
    }

    private static String terminalStatus(Object result) {
        if (result instanceof CompetencyOrchestrationResultDTO outcome) {
            return switch (outcome.status()) {
                case SUCCESS, NO_OP -> "completed";
                case PARTIAL -> "partial";
                case FAILED, IN_PROGRESS -> "failure";
            };
        }
        return "completed";
    }
}

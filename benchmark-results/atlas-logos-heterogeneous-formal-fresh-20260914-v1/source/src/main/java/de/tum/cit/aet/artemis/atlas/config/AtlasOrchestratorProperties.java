package de.tum.cit.aet.artemis.atlas.config;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Orchestrator properties consumed by {@link de.tum.cit.aet.artemis.atlas.service.CompetencyOrchestrationService}
 * and the auto-orchestration pipeline ({@link de.tum.cit.aet.artemis.atlas.service.ContentChangeAccumulatorService},
 * {@link de.tum.cit.aet.artemis.atlas.service.ContentChangeScheduler}). Strict binding catches typos under
 * {@code artemis.atlas.orchestrator}; {@link #temperature()} is ignored when {@link #reasoningEffort()} is
 * non-blank because GPT-5 reasoning models reject explicit temperature.
 *
 * @param model                  Model identifier for the orchestrator chat model.
 * @param temperature            Sampling temperature; ignored when {@link #reasoningEffort()} is non-blank.
 * @param reasoningEffort        Reasoning effort for GPT-5 family models; blank disables reasoning options.
 * @param workerModel            Model identifier for role workers spawned by the orchestrator.
 * @param workerReasoningEffort  Reasoning effort used by role workers.
 * @param responsesApiEnabled    Whether autonomous Atlas rounds use the Responses API adapter.
 * @param debounceWindowSeconds  Seconds without a new content change before a course's accumulator is eligible to fire.
 * @param maxDailyOrchestrations Per-course daily cap on auto-orchestration runs.
 * @param schedulerRateMs        Scheduler tick interval in milliseconds. Also used as the initial delay.
 * @param maxAtlasMLCallsPerRun  Per-run cap on AtlasML similarity-shortlist calls (one per changed exercise); bounds the orchestrator's AtlasML fan-out.
 */
@Validated
@ConfigurationProperties(prefix = "artemis.atlas.orchestrator", ignoreUnknownFields = false)
public record AtlasOrchestratorProperties(@DefaultValue("gpt-5.6-luna") String model, @DefaultValue("1.0") double temperature, @DefaultValue("xhigh") String reasoningEffort,
        @DefaultValue("gpt-5.6-luna") String workerModel, @DefaultValue("high") String workerReasoningEffort, @DefaultValue("false") boolean responsesApiEnabled,
        @DefaultValue("1800") @Positive int debounceWindowSeconds, @DefaultValue("10") @Positive int maxDailyOrchestrations, @DefaultValue("30000") @Positive long schedulerRateMs,
        @DefaultValue("10") @Positive int maxAtlasMLCallsPerRun) {

    /**
     * Binds all record components through Spring Boot when the compatibility constructor is present.
     */
    @ConstructorBinding
    public AtlasOrchestratorProperties {
    }

    /**
     * Retains source compatibility for callers that predate the Responses API switch.
     *
     * @param model                  model identifier for the orchestrator
     * @param temperature            sampling temperature
     * @param reasoningEffort        reasoning effort
     * @param workerModel            model identifier for role workers
     * @param workerReasoningEffort  reasoning effort for role workers
     * @param debounceWindowSeconds  debounce window in seconds
     * @param maxDailyOrchestrations per-course daily cap
     * @param schedulerRateMs        scheduler tick interval
     * @param maxAtlasMLCallsPerRun  per-run AtlasML call cap
     */
    public AtlasOrchestratorProperties(String model, double temperature, String reasoningEffort, String workerModel, String workerReasoningEffort, int debounceWindowSeconds,
            int maxDailyOrchestrations, long schedulerRateMs, int maxAtlasMLCallsPerRun) {
        this(model, temperature, reasoningEffort, workerModel, workerReasoningEffort, false, debounceWindowSeconds, maxDailyOrchestrations, schedulerRateMs, maxAtlasMLCallsPerRun);
    }
}

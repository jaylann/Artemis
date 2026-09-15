package de.tum.cit.aet.artemis.atlas.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_SCHEDULING;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.tum.cit.aet.artemis.atlas.config.AtlasEnabled;
import de.tum.cit.aet.artemis.atlas.dto.AutoOrchestrationSummaryDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyOrchestrationResultDTO;
import de.tum.cit.aet.artemis.atlas.dto.CourseAutoOrchestrationConfigDTO;
import de.tum.cit.aet.artemis.atlas.dto.LearningObjectOutcomeDTO;
import de.tum.cit.aet.artemis.atlas.service.ContentChangeAccumulatorService.BatchClaim;
import de.tum.cit.aet.artemis.communication.service.WebsocketMessagingService;
import de.tum.cit.aet.artemis.core.security.SecurityUtils;
import de.tum.cit.aet.artemis.core.service.feature.Feature;
import de.tum.cit.aet.artemis.core.service.feature.FeatureToggleService;
import de.tum.cit.aet.artemis.course.repository.CourseConfigurationRepository;

/**
 * Tick loop for the automatic competency pipeline. On every scheduled invocation the scheduler
 * asks the accumulator for courses whose debounce window has elapsed and, for each, tries to
 * acquire the scheduler-local lock before claiming the buffered batch. Holding the lock across
 * {@code claimDueBatch} → {@code runBatch} prevents a concurrent tick on another node from
 * draining the same batch twice.
 * <p>
 * The whole claimed batch is handed to {@link CompetencyOrchestrationService#runBatch} in a single
 * orchestrator invocation, so the model reasons across all changed exercises at once rather than
 * one LLM call per exercise.
 */
@Conditional(AtlasEnabled.class)
@Profile(PROFILE_SCHEDULING)
@Lazy
@Component
public class ContentChangeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContentChangeScheduler.class);

    private static final String TOPIC_TEMPLATE = "/topic/atlas/orchestrator/%d";

    private final ContentChangeAccumulatorService accumulator;

    private final CompetencyOrchestrationService orchestrationService;

    private final WebsocketMessagingService websocketMessagingService;

    private final FeatureToggleService featureToggleService;

    private final CourseConfigurationRepository courseConfigurationRepository;

    private final Clock clock;

    public ContentChangeScheduler(ContentChangeAccumulatorService accumulator, CompetencyOrchestrationService orchestrationService,
            WebsocketMessagingService websocketMessagingService, FeatureToggleService featureToggleService, CourseConfigurationRepository courseConfigurationRepository,
            Clock clock) {
        this.accumulator = accumulator;
        this.orchestrationService = orchestrationService;
        this.websocketMessagingService = websocketMessagingService;
        this.featureToggleService = featureToggleService;
        this.courseConfigurationRepository = courseConfigurationRepository;
        this.clock = clock;
    }

    /**
     * Scheduler entry point: every {@code artemis.atlas.orchestrator.scheduler-rate-ms} milliseconds,
     * walk the accumulator for courses whose debounce window has elapsed and drive each through the
     * orchestrator under the per-course lock. A no-op when the feature toggle is disabled so the
     * toggle is a zero-cost operational kill switch.
     */
    @Scheduled(fixedRateString = "${artemis.atlas.orchestrator.scheduler-rate-ms:30000}", initialDelayString = "${artemis.atlas.orchestrator.scheduler-rate-ms:30000}")
    public void tick() {
        SecurityUtils.setSystemAuthorizationObject();
        if (!featureToggleService.isFeatureEnabled(Feature.AtlasAgent)) {
            return;
        }
        Set<Long> dueCourses;
        try {
            dueCourses = accumulator.listDueCourseIds();
        }
        catch (Exception ex) {
            log.warn("atlas.automatic scheduler failed to list due courses: {}", ex.getMessage());
            return;
        }
        for (Long courseId : dueCourses) {
            try {
                processCourse(courseId);
            }
            catch (Exception ex) {
                log.warn("atlas.automatic scheduler failed for course {}: {}", courseId, ex.getMessage(), ex);
            }
        }
    }

    private void processCourse(long courseId) {
        // Resolve the per-course config exactly once per tick and thread the result through the
        // kill-switch decision and the claim path, so a due course costs a single config query instead
        // of one per kill-switch / window / cap lookup.
        CourseAutoOrchestrationConfigDTO config = courseConfigurationRepository.findAutoOrchestrationConfigByCourseId(courseId).orElse(null);
        // Per-course kill switch: a course that disabled auto-orchestration after buffering changes
        // must never fire. Flush its bucket so the buffered ids are dropped rather than draining the
        // next time it is (possibly) re-enabled, and skip the run.
        boolean autoOrchestratorEnabled = config != null && config.autoOrchestratorEnabled();
        if (!autoOrchestratorEnabled) {
            log.debug("atlas.automatic scheduler skipping course {}: auto-orchestration disabled; flushing bucket", courseId);
            accumulator.flush(courseId);
            return;
        }
        // The lock-guarded claimDueBatch atomically drains and resets the bucket, so only one
        // scheduler tick — on any node — ever receives a non-empty batch for a given course. The
        // subsequent orchestration is additionally guarded by the per-course run lock in
        // CompetencyOrchestrationService, so no separate scheduler lock is needed here. The window and
        // cap come from the config already resolved above, so the claim performs no extra query.
        int debounceWindowSeconds = accumulator.resolveDebounceWindowSeconds(config);
        int dailyCap = accumulator.resolveDailyCap(config);
        Optional<BatchClaim> maybeClaim = accumulator.claimDueBatch(courseId, debounceWindowSeconds, dailyCap);
        if (maybeClaim.isEmpty()) {
            return;
        }
        String runId = UUID.randomUUID().toString();
        processBatch(courseId, runId, maybeClaim.get());
    }

    private void processBatch(long courseId, String runId, BatchClaim claim) {
        Set<Long> exerciseIds = claim.exerciseIds();
        Set<Long> lectureUnitIds = claim.lectureUnitIds();
        log.info("atlas.automatic course {} firing run {} with {} exercise(s) and {} lecture unit(s)", courseId, runId, exerciseIds.size(), lectureUnitIds.size());

        CompetencyOrchestrationResultDTO result;
        try {
            result = orchestrationService.runBatch(courseId, exerciseIds, lectureUnitIds);
        }
        catch (Exception ex) {
            // An exception escapes runBatch only from batch preparation (learning-object resolution / run
            // claim), before any competency is mutated — runBatch's lock release is best-effort and
            // cannot throw here — so the changes are safe to requeue rather than discard.
            log.warn("atlas.automatic batch run failed for course {} (run {}): {}", courseId, runId, ex.getMessage(), ex);
            accumulator.requeueAfterFailedRun(courseId, exerciseIds, lectureUnitIds);
            List<LearningObjectOutcomeDTO> outcomes = syntheticOutcomes(exerciseIds, lectureUnitIds, LearningObjectOutcomeDTO.Status.FAILED, true,
                    "Batch preparation failed before orchestration.");
            broadcastSummary(courseId, runId, CompetencyOrchestrationResultDTO.Status.FAILED, outcomes);
            return;
        }

        CompetencyOrchestrationResultDTO.Status status = result == null ? CompetencyOrchestrationResultDTO.Status.FAILED : result.status();
        List<LearningObjectOutcomeDTO> outcomes = normalizeOutcomes(exerciseIds, lectureUnitIds, status, result == null ? List.of() : result.objectOutcomes());
        if (result != null && result.failureReason() == CompetencyOrchestrationResultDTO.FailureReason.TOOL_CALL_LIMIT_EXCEEDED) {
            outcomes = outcomes.stream().map(outcome -> new LearningObjectOutcomeDTO(outcome.objectType(), outcome.objectId(), outcome.status(), false, result.summary())).toList();
            broadcastSummary(courseId, runId, status, outcomes);
            return;
        }
        Set<Long> retryExerciseIds = retryEligibleIds(outcomes, LearningObjectOutcomeDTO.ObjectType.EXERCISE);
        Set<Long> retryLectureUnitIds = retryEligibleIds(outcomes, LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT);
        switch (status) {
            case IN_PROGRESS -> {
                // A concurrent course run never consumed retry-eligible objects. Requeue precisely those
                // objects and refund the reservation; unsupported objects remain terminally skipped.
                log.debug("atlas.automatic course {} run {} deferred {} exercise(s) and {} lecture unit(s); no summary broadcast", courseId, runId, retryExerciseIds.size(),
                        retryLectureUnitIds.size());
                requeueConcurrent(courseId, retryExerciseIds, retryLectureUnitIds);
            }
            case NO_OP -> {
                requeueFailed(courseId, retryExerciseIds, retryLectureUnitIds);
                if (outcomes.stream().allMatch(outcome -> outcome.status() == LearningObjectOutcomeDTO.Status.SKIPPED)) {
                    log.debug("atlas.automatic course {} run {} had no applicable changes; no summary broadcast", courseId, runId);
                }
                else {
                    broadcastSummary(courseId, runId, status, outcomes);
                }
            }
            case FAILED -> {
                requeueFailed(courseId, retryExerciseIds, retryLectureUnitIds);
                broadcastSummary(courseId, runId, status, outcomes);
            }
            case PARTIAL, SUCCESS -> {
                // PARTIAL objects themselves are not retry eligible because mutations may already have
                // committed. Independent pre-mutation extraction failures remain safe to requeue.
                requeueFailed(courseId, retryExerciseIds, retryLectureUnitIds);
                broadcastSummary(courseId, runId, status, outcomes);
            }
        }
    }

    private void requeueConcurrent(long courseId, Set<Long> exerciseIds, Set<Long> lectureUnitIds) {
        if (!exerciseIds.isEmpty() || !lectureUnitIds.isEmpty()) {
            accumulator.requeueAfterConcurrentRun(courseId, exerciseIds, lectureUnitIds);
        }
    }

    private void requeueFailed(long courseId, Set<Long> exerciseIds, Set<Long> lectureUnitIds) {
        if (!exerciseIds.isEmpty() || !lectureUnitIds.isEmpty()) {
            accumulator.requeueAfterFailedRun(courseId, exerciseIds, lectureUnitIds);
        }
    }

    private void broadcastSummary(long courseId, String runId, CompetencyOrchestrationResultDTO.Status status, List<LearningObjectOutcomeDTO> outcomes) {
        int successCount = (int) outcomes.stream().filter(outcome -> outcome.status() == LearningObjectOutcomeDTO.Status.PROCESSED).count();
        int skippedCount = (int) outcomes.stream().filter(outcome -> outcome.status() == LearningObjectOutcomeDTO.Status.SKIPPED).count();
        int failureCount = outcomes.size() - successCount - skippedCount;
        AutoOrchestrationSummaryDTO summary = new AutoOrchestrationSummaryDTO(courseId, runId, status, outcomes.size(), successCount, failureCount, skippedCount, outcomes,
                Instant.now(clock));
        websocketMessagingService.sendMessage(TOPIC_TEMPLATE.formatted(courseId), summary);
    }

    private static List<LearningObjectOutcomeDTO> normalizeOutcomes(Set<Long> exerciseIds, Set<Long> lectureUnitIds, CompetencyOrchestrationResultDTO.Status batchStatus,
            List<LearningObjectOutcomeDTO> reportedOutcomes) {
        LearningObjectOutcomeDTO.Status fallbackStatus;
        boolean fallbackRetryEligible;
        switch (batchStatus) {
            case SUCCESS -> {
                fallbackStatus = LearningObjectOutcomeDTO.Status.PROCESSED;
                fallbackRetryEligible = false;
            }
            case PARTIAL -> {
                fallbackStatus = LearningObjectOutcomeDTO.Status.PARTIAL;
                fallbackRetryEligible = false;
            }
            case FAILED -> {
                fallbackStatus = LearningObjectOutcomeDTO.Status.FAILED;
                fallbackRetryEligible = true;
            }
            case IN_PROGRESS -> {
                fallbackStatus = LearningObjectOutcomeDTO.Status.DEFERRED;
                fallbackRetryEligible = true;
            }
            case NO_OP -> {
                fallbackStatus = LearningObjectOutcomeDTO.Status.SKIPPED;
                fallbackRetryEligible = false;
            }
            default -> throw new IllegalStateException("Unsupported orchestration status: " + batchStatus);
        }
        Map<LearningObjectKey, LearningObjectOutcomeDTO> outcomes = new LinkedHashMap<>();
        syntheticOutcomes(exerciseIds, lectureUnitIds, fallbackStatus, fallbackRetryEligible, "No finer-grained outcome was reported.")
                .forEach(outcome -> outcomes.put(new LearningObjectKey(outcome.objectType(), outcome.objectId()), outcome));
        for (LearningObjectOutcomeDTO outcome : reportedOutcomes) {
            LearningObjectKey key = new LearningObjectKey(outcome.objectType(), outcome.objectId());
            if (outcomes.containsKey(key)) {
                outcomes.put(key, outcome);
            }
            else {
                log.warn("atlas.automatic ignored outcome for unclaimed {} {}", outcome.objectType(), outcome.objectId());
            }
        }
        return outcomes.values().stream().sorted(Comparator.comparing(LearningObjectOutcomeDTO::objectType).thenComparingLong(LearningObjectOutcomeDTO::objectId)).toList();
    }

    private static List<LearningObjectOutcomeDTO> syntheticOutcomes(Set<Long> exerciseIds, Set<Long> lectureUnitIds, LearningObjectOutcomeDTO.Status status, boolean retryEligible,
            String detail) {
        List<LearningObjectOutcomeDTO> outcomes = new ArrayList<>();
        exerciseIds.stream().sorted().map(id -> new LearningObjectOutcomeDTO(LearningObjectOutcomeDTO.ObjectType.EXERCISE, id, status, retryEligible, detail))
                .forEach(outcomes::add);
        lectureUnitIds.stream().sorted().map(id -> new LearningObjectOutcomeDTO(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, id, status, retryEligible, detail))
                .forEach(outcomes::add);
        return List.copyOf(outcomes);
    }

    private static Set<Long> retryEligibleIds(List<LearningObjectOutcomeDTO> outcomes, LearningObjectOutcomeDTO.ObjectType objectType) {
        return outcomes.stream().filter(LearningObjectOutcomeDTO::retryEligible).filter(outcome -> outcome.objectType() == objectType).map(LearningObjectOutcomeDTO::objectId)
                .collect(Collectors.toSet());
    }

    private record LearningObjectKey(LearningObjectOutcomeDTO.ObjectType objectType, long objectId) {
    }
}

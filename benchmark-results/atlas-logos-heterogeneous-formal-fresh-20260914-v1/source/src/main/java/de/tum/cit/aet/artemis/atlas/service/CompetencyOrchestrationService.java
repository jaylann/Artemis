package de.tum.cit.aet.artemis.atlas.service;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.admin.domain.LLMServiceType;
import de.tum.cit.aet.artemis.admin.service.LLMTokenUsageService;
import de.tum.cit.aet.artemis.atlas.config.AtlasEnabled;
import de.tum.cit.aet.artemis.atlas.config.AtlasOrchestratorProperties;
import de.tum.cit.aet.artemis.atlas.config.AtlasToolSurface;
import de.tum.cit.aet.artemis.atlas.dto.AppliedActionDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyIndexDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyIndexResponseDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyOrchestrationResultDTO;
import de.tum.cit.aet.artemis.atlas.dto.ExtractedContentDTO;
import de.tum.cit.aet.artemis.atlas.dto.LearningObjectOutcomeDTO;
import de.tum.cit.aet.artemis.atlas.dto.OrchestrationCompletionDTO;
import de.tum.cit.aet.artemis.atlas.service.ContentChangeAccumulatorService.BatchClaim;
import de.tum.cit.aet.artemis.atlas.service.OrchestratorToolContextKeys.AppliedActionsBuffer;
import de.tum.cit.aet.artemis.atlas.service.atlasml.AtlasMLShortlistService;
import de.tum.cit.aet.artemis.atlas.service.util.AtlasPromptSanitizer;
import de.tum.cit.aet.artemis.core.security.SecurityUtils;
import de.tum.cit.aet.artemis.core.service.distributed.api.DistributedDataProvider;
import de.tum.cit.aet.artemis.core.service.distributed.api.map.DistributedMap;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;
import de.tum.cit.aet.artemis.lecture.api.LectureUnitRepositoryApi;
import de.tum.cit.aet.artemis.lecture.domain.ExerciseUnit;
import de.tum.cit.aet.artemis.lecture.domain.LectureUnit;

/**
 * Entry point for autonomous competency management runs.
 * <p>
 * {@link #run(long)} drives a tool-calling LLM loop through the shared {@link AtlasAgentDelegationService}
 * harness: the model is given the exercise as anchor text and can call the orchestrator read/planning
 * tools ({@link OrchestratorReadToolsService}, {@link OrchestratorPlanningToolsService}) to inspect
 * course state and the five write tools ({@code createCompetency}, {@code editCompetency},
 * {@code assignExerciseToCompetency}, {@code unassignExerciseFromCompetency}, {@code deleteCompetency},
 * split across {@link CreatorToolsService} / {@link EditorToolsService} / {@link AssignerToolsService})
 * to mutate it. Every successful mutation is appended to a per-run applied-actions list held in the
 * Spring AI {@code ToolContext}.
 * <p>
 * Course context is injected via {@code ToolContext} (see {@link OrchestratorToolContextKeys}) so the
 * LLM cannot forge the course id through tool arguments.
 */
@Conditional(AtlasEnabled.class)
@Lazy
@Service
public class CompetencyOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(CompetencyOrchestrationService.class);

    private static final String EXECUTE_PROMPT_PATH = "prompts/atlas/orchestrator_execute_prompt.st";

    private static final String RUN_MAP_NAME = "atlas-orchestrator-runs";

    /** Token-usage pipeline id for one orchestrator LLM round. */
    private static final String ORCHESTRATION_PIPELINE_ID = "ATLAS_ORCHESTRATION";

    /** Stale-claim lease: a run claim older than this is reclaimed, so a crashed node can't wedge a course in IN_PROGRESS (Redis/Local maps have no TTL). */
    private static final Duration RUN_LEASE = Duration.ofMinutes(30);

    /** Length caps on instructor-controlled strings to bound prompt size and injection surface. */
    private static final int EXERCISE_TITLE_MAX = 200;

    private static final int PROBLEM_STATEMENT_MAX = 8_000;

    private static final int COMPETENCY_TITLE_MAX = 200;

    private static final int LECTURE_UNIT_NAME_MAX = 200;

    private static final int TYPE_LABEL_MAX = 50;

    private final ExerciseRepository exerciseRepository;

    private final Optional<LectureUnitRepositoryApi> lectureUnitRepositoryApi;

    private final ContentExtractionService contentExtractionService;

    private final OrchestratorPlanningToolsService orchestratorPlanningToolsService;

    private final AtlasPromptTemplateService templateService;

    private final AtlasAgentDelegationService delegationService;

    @Nullable
    private final ChatClient chatClient;

    private final ToolCallbackProvider orchestratorReadToolCallbackProvider;

    private final ToolCallbackProvider orchestratorPlanningToolCallbackProvider;

    private final ToolCallbackProvider orchestratorDelegationToolCallbackProvider;

    private final ToolCallbackProvider orchestratorTerminalToolCallbackProvider;

    private final String deploymentName;

    private final double temperature;

    private final String reasoningEffort;

    private final Optional<DistributedDataProvider> distributedDataProvider;

    private final ContentChangeAccumulatorService contentChangeAccumulatorService;

    private final LLMTokenUsageService llmTokenUsageService;

    private final UserRepository userRepository;

    private final AtlasMLShortlistService shortlistService;

    private volatile DistributedMap<Long, RunInfo> runMap;

    public CompetencyOrchestrationService(ExerciseRepository exerciseRepository, Optional<LectureUnitRepositoryApi> lectureUnitRepositoryApi,
            ContentExtractionService contentExtractionService, OrchestratorPlanningToolsService orchestratorPlanningToolsService, AtlasPromptTemplateService templateService,
            AtlasAgentDelegationService delegationService, @Nullable ChatClient chatClient,
            @Qualifier("orchestratorReadToolCallbackProvider") AtlasToolSurface orchestratorReadToolCallbackProvider,
            @Qualifier("orchestratorPlanningToolCallbackProvider") AtlasToolSurface orchestratorPlanningToolCallbackProvider,
            @Qualifier("orchestratorDelegationToolCallbackProvider") AtlasToolSurface orchestratorDelegationToolCallbackProvider,
            @Qualifier("orchestratorTerminalToolCallbackProvider") AtlasToolSurface orchestratorTerminalToolCallbackProvider,
            Optional<DistributedDataProvider> distributedDataProvider, AtlasOrchestratorProperties properties, ContentChangeAccumulatorService contentChangeAccumulatorService,
            LLMTokenUsageService llmTokenUsageService, UserRepository userRepository, AtlasMLShortlistService shortlistService) {
        this.exerciseRepository = exerciseRepository;
        this.lectureUnitRepositoryApi = lectureUnitRepositoryApi;
        this.contentExtractionService = contentExtractionService;
        this.orchestratorPlanningToolsService = orchestratorPlanningToolsService;
        this.templateService = templateService;
        this.delegationService = delegationService;
        this.chatClient = chatClient;
        this.orchestratorReadToolCallbackProvider = orchestratorReadToolCallbackProvider.provider();
        this.orchestratorPlanningToolCallbackProvider = orchestratorPlanningToolCallbackProvider.provider();
        this.orchestratorDelegationToolCallbackProvider = orchestratorDelegationToolCallbackProvider.provider();
        this.orchestratorTerminalToolCallbackProvider = orchestratorTerminalToolCallbackProvider.provider();
        this.deploymentName = properties.model();
        this.temperature = properties.temperature();
        this.reasoningEffort = properties.reasoningEffort();
        this.distributedDataProvider = distributedDataProvider;
        this.contentChangeAccumulatorService = contentChangeAccumulatorService;
        this.llmTokenUsageService = llmTokenUsageService;
        this.userRepository = userRepository;
        this.shortlistService = shortlistService;
    }

    /** Per-course IN_PROGRESS guard map, resolved lazily (see {@link #resolveRunMap}). */
    private DistributedMap<Long, RunInfo> runMap() {
        DistributedMap<Long, RunInfo> resolved = runMap;
        if (resolved == null) {
            synchronized (this) {
                resolved = runMap;
                if (resolved == null) {
                    resolved = resolveRunMap();
                    runMap = resolved;
                }
            }
        }
        return resolved;
    }

    private DistributedMap<Long, RunInfo> resolveRunMap() {
        return distributedDataProvider
                .orElseThrow(() -> new IllegalStateException("Atlas auto-orchestration requires a clustered DistributedDataProvider (localci/buildagent profile active)."))
                .getMap(RUN_MAP_NAME);
    }

    /** Claim the per-course run lock: returns {@code null} if acquired, else the active (non-stale) {@link RunInfo}. Reclaims {@link #RUN_LEASE}-stale entries. */
    @Nullable
    private RunInfo claimRun(long courseId, RunInfo claim) {
        DistributedMap<Long, RunInfo> currentMap = runMap();
        currentMap.lock(courseId);
        try {
            RunInfo existing = currentMap.get(courseId);
            if (existing != null && !isStale(existing, claim.startedAt())) {
                return existing;
            }
            currentMap.put(courseId, claim);
            return null;
        }
        finally {
            currentMap.unlock(courseId);
        }
    }

    /** Stale once {@code startedAt} is null or older than {@link #RUN_LEASE} before {@code now} (cross-node clocks; 30 min absorbs skew + run time). */
    private static boolean isStale(RunInfo existing, @Nullable Instant now) {
        if (existing.startedAt() == null) {
            return true;
        }
        return now != null && existing.startedAt().isBefore(now.minus(RUN_LEASE));
    }

    /**
     * Release the per-course run lock, but only if it still holds {@code claim} — a TTL-evicted entry
     * replaced by another claim is left untouched (compare-and-remove).
     * <p>
     * Best-effort: this runs in the callers' {@code finally} blocks after {@code orchestrateBatch} /
     * {@code orchestrateExercise} may already have committed competency mutations. A distributed-map
     * failure here (e.g. {@code HazelcastInstanceNotActiveException} during member shutdown or
     * partition migration) must not propagate, or it would clobber the already-computed result and —
     * for the scheduler — be mistaken for a safe pre-mutation failure that triggers a re-run of an
     * already-applied batch. A failed release just leaves the entry to expire via {@link #RUN_LEASE}.
     */
    private void releaseRun(long courseId, RunInfo claim) {
        try {
            DistributedMap<Long, RunInfo> currentMap = runMap();
            currentMap.lock(courseId);
            try {
                if (claim.equals(currentMap.get(courseId))) {
                    currentMap.remove(courseId);
                }
            }
            finally {
                currentMap.unlock(courseId);
            }
        }
        catch (Exception ex) {
            log.warn("Atlas orchestrator failed to release run lock for course {} (run {}); lease will self-expire: {}", courseId, claim.runId(), ex.getMessage(), ex);
        }
    }

    /**
     * Run one orchestration pass for the given programming exercise. The orchestrator plans
     * internally and executes its plan by calling write tools — each tool call mutates state
     * immediately and appends to the applied-actions list returned in the result.
     *
     * @param exerciseId the programming exercise to orchestrate competencies for
     * @return one of:
     *         <ul>
     *         <li>{@link CompetencyOrchestrationResultDTO.Status#SUCCESS} with the LLM's summary message and the applied actions;</li>
     *         <li>{@link CompetencyOrchestrationResultDTO.Status#PARTIAL} when the LLM threw after committing at least one action — the partial audit trail is included so the
     *         caller can review/revert;</li>
     *         <li>{@link CompetencyOrchestrationResultDTO.Status#FAILED} with {@link CompetencyOrchestrationResultDTO.FailureReason#LLM_ERROR} if the call failed before any action
     *         committed, or {@link CompetencyOrchestrationResultDTO.FailureReason#NO_CHAT_CLIENT} if no ChatClient is configured;</li>
     *         <li>{@link CompetencyOrchestrationResultDTO.Status#IN_PROGRESS} if a run is already active for the same course.</li>
     *         </ul>
     */
    public CompetencyOrchestrationResultDTO run(long exerciseId) {
        Exercise exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        CompetencyOrchestrationResultDTO precheck = precheckExercise(exercise);
        if (precheck != null) {
            return precheck;
        }
        long courseId = exercise.getCourseViaExerciseGroupOrCourseMember().getId();

        RunInfo claim = new RunInfo(UUID.randomUUID().toString(), exerciseId, Instant.now());
        RunInfo existing = claimRun(courseId, claim);
        if (existing != null) {
            log.info("Atlas orchestrator rejected for exercise {} (course {}): run {} already in progress for exercise {}", exerciseId, courseId, existing.runId(),
                    existing.exerciseId());
            return CompetencyOrchestrationResultDTO.inProgress("Another Atlas orchestrator run is already in progress for this course. Please wait for it to finish.")
                    .withObjectOutcomes(List.of(
                            outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, LearningObjectOutcomeDTO.Status.DEFERRED, true, "Another course run is active.")));
        }
        try {
            return orchestrateExercise(exercise, courseId);
        }
        finally {
            releaseRun(courseId, claim);
        }
    }

    /**
     * Runs the automatic pipeline over a whole accumulated batch in a single orchestrator
     * invocation: all changed exercises are rendered into one EXERCISE CHANGE BATCH and reasoned
     * over in one LLM call, rather than one call per exercise. Exam and unknown exercises, plus any
     * whose owning course does not match {@code courseId}, are recorded as skipped. Holds the per-course
     * {@link #runMap} claim once for the whole batch — a concurrent manual run or scheduled tick
     * observes {@link CompetencyOrchestrationResultDTO.Status#IN_PROGRESS}.
     *
     * @param courseId    the course whose buffered batch is being drained
     * @param exerciseIds programming-exercise ids in the batch
     * @return the single batch result; {@code SUCCESS} when the run completed, {@code NO_OP} when no
     *         claimed exercise was applicable (so nothing was processed), {@code IN_PROGRESS} when
     *         another run holds the course lock
     */
    public CompetencyOrchestrationResultDTO runBatch(long courseId, Set<Long> exerciseIds) {
        return runBatch(courseId, exerciseIds, Set.of());
    }

    /**
     * Runs the automatic pipeline over a whole accumulated batch of changed exercises AND lecture units
     * in a single orchestrator invocation. Both are resolved (exam/unknown/wrong-course exercises and
     * ExerciseUnit/unknown/wrong-course lecture units are recorded as skipped) and rendered into one change
     * batch reasoned over in one LLM call. Holds the per-course {@link #runMap} claim once for the whole
     * batch — a concurrent manual run or scheduled tick observes {@link CompetencyOrchestrationResultDTO.Status#IN_PROGRESS}.
     *
     * @param courseId       the course whose buffered batch is being drained
     * @param exerciseIds    exercise ids in the batch
     * @param lectureUnitIds lecture-unit ids in the batch
     * @return the single batch result; {@code SUCCESS} when the run completed, {@code NO_OP} when no
     *         claimed learning object was applicable, {@code IN_PROGRESS} when another run holds the lock
     */
    public CompetencyOrchestrationResultDTO runBatch(long courseId, Set<Long> exerciseIds, Set<Long> lectureUnitIds) {
        List<Exercise> exercises = resolveBatchExercises(courseId, exerciseIds);
        List<LectureUnit> lectureUnits = resolveBatchLectureUnits(courseId, lectureUnitIds);
        List<LearningObjectOutcomeDTO> initialOutcomes = skippedResolutionOutcomes(exerciseIds, lectureUnitIds, exercises, lectureUnits);
        if (exercises.isEmpty() && lectureUnits.isEmpty()) {
            return CompetencyOrchestrationResultDTO.noOp("No applicable exercises or lecture units in batch.").withObjectOutcomes(initialOutcomes);
        }
        if (chatClient == null) {
            return CompetencyOrchestrationResultDTO.failed("Atlas chat model is not configured.", CompetencyOrchestrationResultDTO.FailureReason.NO_CHAT_CLIENT)
                    .withObjectOutcomes(appendResolvedOutcomes(initialOutcomes, exercises, lectureUnits, LearningObjectOutcomeDTO.Status.FAILED, true, "Chat model unavailable."));
        }

        long anchorId = exercises.isEmpty() ? lectureUnits.getFirst().getId() : exercises.getFirst().getId();
        RunInfo claim = new RunInfo(UUID.randomUUID().toString(), anchorId, Instant.now());
        RunInfo existing = claimRun(courseId, claim);
        if (existing != null) {
            log.info("Atlas orchestrator (batch) rejected for course {}: run {} already in progress for exercise {}", courseId, existing.runId(), existing.exerciseId());
            return CompetencyOrchestrationResultDTO.inProgress("Another Atlas orchestrator run is already in progress for this course. Please wait for it to finish.")
                    .withObjectOutcomes(
                            appendResolvedOutcomes(initialOutcomes, exercises, lectureUnits, LearningObjectOutcomeDTO.Status.DEFERRED, true, "Another course run is active."));
        }
        try {
            return orchestrateBatch(exercises, lectureUnits, courseId, initialOutcomes);
        }
        finally {
            releaseRun(courseId, claim);
        }
    }

    /**
     * Runs the manual "suggest competencies" flow: force-drains the course's accumulator
     * (bypassing the debounce window) and merges the clicked exercise into the drained set, so any
     * pending changes plus the clicked exercise are reasoned over in a single batched LLM call. The
     * returned result therefore covers the whole batch, not just the clicked exercise. Holds the
     * per-course run lock for the whole batch — a concurrent manual press or a scheduled tick
     * observes IN_PROGRESS while we are running.
     *
     * @param exerciseId the manually triggered exercise (always processed, even when not queued)
     * @return the single batch result covering the clicked exercise and any queued changes
     */
    public CompetencyOrchestrationResultDTO runWithQueuedFlush(long exerciseId) {
        Exercise clicked = exerciseRepository.findByIdElseThrow(exerciseId);
        CompetencyOrchestrationResultDTO precheck = precheckExercise(clicked);
        if (precheck != null) {
            return precheck;
        }
        long courseId = clicked.getCourseViaExerciseGroupOrCourseMember().getId();

        RunInfo claim = new RunInfo(UUID.randomUUID().toString(), exerciseId, Instant.now());
        RunInfo existing = claimRun(courseId, claim);
        if (existing != null) {
            log.info("Atlas orchestrator (manual flush) rejected for exercise {} (course {}): run {} already in progress for exercise {}", exerciseId, courseId, existing.runId(),
                    existing.exerciseId());
            return CompetencyOrchestrationResultDTO.inProgress("Another Atlas orchestrator run is already in progress for this course. Please wait for it to finish.")
                    .withObjectOutcomes(List.of(
                            outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, LearningObjectOutcomeDTO.Status.DEFERRED, true, "Another course run is active.")));
        }
        try {
            Optional<BatchClaim> drained = contentChangeAccumulatorService.claimBatchNow(courseId);
            Set<Long> queuedExerciseIds = drained.map(BatchClaim::exerciseIds).orElseGet(Set::of);
            Set<Long> queuedLectureUnitIds = drained.map(BatchClaim::lectureUnitIds).orElseGet(Set::of);
            // Queued changes first, clicked exercise last; a LinkedHashSet dedupes the clicked id if
            // it was also queued so it is rendered (and run) only once.
            Set<Long> mergedExerciseIds = new LinkedHashSet<>(queuedExerciseIds);
            mergedExerciseIds.add(exerciseId);
            log.info("Atlas orchestrator (manual flush) course {} running batch of {} exercise(s) and {} lecture unit(s) (including clicked exercise {})", courseId,
                    mergedExerciseIds.size(), queuedLectureUnitIds.size(), exerciseId);
            List<Exercise> exercises = resolveBatchExercises(courseId, mergedExerciseIds);
            List<LectureUnit> lectureUnits = resolveBatchLectureUnits(courseId, queuedLectureUnitIds);
            List<LearningObjectOutcomeDTO> initialOutcomes = skippedResolutionOutcomes(mergedExerciseIds, queuedLectureUnitIds, exercises, lectureUnits);
            if (exercises.isEmpty() && lectureUnits.isEmpty()) {
                return CompetencyOrchestrationResultDTO.noOp("No applicable exercises or lecture units in batch.").withObjectOutcomes(initialOutcomes);
            }
            CompetencyOrchestrationResultDTO result = orchestrateBatch(exercises, lectureUnits, courseId, initialOutcomes);
            requeueRetryEligible(courseId, result.objectOutcomes());
            return result;
        }
        finally {
            releaseRun(courseId, claim);
        }
    }

    /**
     * Manual lecture-unit trigger that flushes and processes the course's complete queued batch.
     *
     * @param lectureUnitId content-bearing lecture unit to include in the run
     * @return the verified orchestration result or a typed failure
     */
    public CompetencyOrchestrationResultDTO runLectureUnitWithQueuedFlush(long lectureUnitId) {
        if (lectureUnitRepositoryApi.isEmpty()) {
            return CompetencyOrchestrationResultDTO.failed("Lecture units are unavailable.", CompetencyOrchestrationResultDTO.FailureReason.UNSUPPORTED_LEARNING_OBJECT)
                    .withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnitId, LearningObjectOutcomeDTO.Status.FAILED, true,
                            "Lecture-unit repository unavailable.")));
        }
        LectureUnit clicked = lectureUnitRepositoryApi.get().findWithLectureById(lectureUnitId).orElse(null);
        if (clicked == null || clicked instanceof ExerciseUnit || clicked.getLecture() == null || clicked.getLecture().getCourse() == null) {
            return CompetencyOrchestrationResultDTO
                    .failed("Atlas orchestrator only operates on content-bearing course lecture units.", CompetencyOrchestrationResultDTO.FailureReason.UNSUPPORTED_LEARNING_OBJECT)
                    .withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnitId, LearningObjectOutcomeDTO.Status.SKIPPED, false,
                            "Unavailable, unsupported, or not course-owned.")));
        }
        if (chatClient == null) {
            return CompetencyOrchestrationResultDTO.failed("Atlas chat model is not configured.", CompetencyOrchestrationResultDTO.FailureReason.NO_CHAT_CLIENT).withObjectOutcomes(
                    List.of(outcome(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnitId, LearningObjectOutcomeDTO.Status.FAILED, true, "Chat model unavailable.")));
        }
        long courseId = clicked.getLecture().getCourse().getId();
        RunInfo claim = new RunInfo(UUID.randomUUID().toString(), lectureUnitId, Instant.now());
        RunInfo existing = claimRun(courseId, claim);
        if (existing != null) {
            return CompetencyOrchestrationResultDTO.inProgress("Another Atlas orchestrator run is already in progress for this course. Please wait for it to finish.")
                    .withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnitId, LearningObjectOutcomeDTO.Status.DEFERRED, true,
                            "Another course run is active.")));
        }
        try {
            Optional<BatchClaim> drained = contentChangeAccumulatorService.claimBatchNow(courseId);
            Set<Long> exerciseIds = new LinkedHashSet<>(drained.map(BatchClaim::exerciseIds).orElseGet(Set::of));
            Set<Long> lectureUnitIds = new LinkedHashSet<>(drained.map(BatchClaim::lectureUnitIds).orElseGet(Set::of));
            lectureUnitIds.add(lectureUnitId);
            List<Exercise> exercises = resolveBatchExercises(courseId, exerciseIds);
            List<LectureUnit> lectureUnits = resolveBatchLectureUnits(courseId, lectureUnitIds);
            List<LearningObjectOutcomeDTO> initialOutcomes = skippedResolutionOutcomes(exerciseIds, lectureUnitIds, exercises, lectureUnits);
            if (lectureUnits.stream().noneMatch(unit -> unit.getId().equals(lectureUnitId))) {
                CompetencyOrchestrationResultDTO result = CompetencyOrchestrationResultDTO
                        .failed("Lecture unit is not eligible for orchestration.", CompetencyOrchestrationResultDTO.FailureReason.UNSUPPORTED_LEARNING_OBJECT)
                        .withObjectOutcomes(appendResolvedOutcomes(initialOutcomes, exercises, lectureUnits, LearningObjectOutcomeDTO.Status.DEFERRED, true,
                                "Manual trigger became unavailable before orchestration."));
                requeueRetryEligible(courseId, result.objectOutcomes());
                return result;
            }
            CompetencyOrchestrationResultDTO result = orchestrateBatch(exercises, lectureUnits, courseId, initialOutcomes);
            requeueRetryEligible(courseId, result.objectOutcomes());
            return result;
        }
        finally {
            releaseRun(courseId, claim);
        }
    }

    /**
     * Resolves a set of exercise ids into the programming exercises eligible for orchestration,
     * dropping unknown and exam exercises and — as a defence against a stale/corrupt accumulator
     * entry — any whose owning course does not match {@code courseId} (mixing course content is
     * never correct). Order of {@code exerciseIds} is preserved.
     */
    private List<Exercise> resolveBatchExercises(long courseId, Collection<Long> exerciseIds) {
        Map<Long, Exercise> byId = new HashMap<>();
        for (Exercise exercise : exerciseRepository.findAllById(exerciseIds)) {
            byId.put(exercise.getId(), exercise);
        }
        List<Exercise> exercises = new ArrayList<>();
        for (Long id : exerciseIds) {
            Exercise exercise = byId.get(id);
            if (exercise == null) {
                log.info("Atlas orchestrator (batch) skipping exercise {}: not found", id);
                continue;
            }
            if (exercise.isExamExercise()) {
                log.info("Atlas orchestrator (batch) skipping exam exercise {}", id);
                continue;
            }
            var course = exercise.getCourseViaExerciseGroupOrCourseMember();
            if (course == null || course.getId() == null || course.getId() != courseId) {
                log.warn("Atlas orchestrator (batch) skipping exercise {}: course ownership mismatch (expected {}, got {})", id, courseId, course == null ? null : course.getId());
                continue;
            }
            exercises.add(exercise);
        }
        return exercises;
    }

    /**
     * Resolves a set of lecture-unit ids into the units eligible for orchestration, dropping unknown
     * units, {@link ExerciseUnit}s (never orchestrated — {@code CourseCompetency.prePersistOrUpdate}
     * strips their links) and — as a defence against a stale/corrupt accumulator entry — any whose
     * owning course does not match {@code courseId}. The lecture (and its course) is fetch-joined so
     * the course-ownership check needs no lazy traversal. Order of {@code lectureUnitIds} is preserved.
     * Returns an empty list immediately when no ids are requested or the lecture module is unavailable.
     */
    private List<LectureUnit> resolveBatchLectureUnits(long courseId, Collection<Long> lectureUnitIds) {
        if (lectureUnitIds.isEmpty() || lectureUnitRepositoryApi.isEmpty()) {
            return List.of();
        }
        Map<Long, LectureUnit> byId = new HashMap<>();
        for (LectureUnit lectureUnit : lectureUnitRepositoryApi.get().findAllByIdsWithLecture(lectureUnitIds)) {
            byId.put(lectureUnit.getId(), lectureUnit);
        }
        List<LectureUnit> lectureUnits = new ArrayList<>();
        for (Long id : lectureUnitIds) {
            LectureUnit lectureUnit = byId.get(id);
            if (lectureUnit == null) {
                log.info("Atlas orchestrator (batch) skipping lecture unit {}: not found", id);
                continue;
            }
            if (lectureUnit instanceof ExerciseUnit) {
                log.info("Atlas orchestrator (batch) skipping exercise-backed lecture unit {}", id);
                continue;
            }
            var lecture = lectureUnit.getLecture();
            var course = lecture == null ? null : lecture.getCourse();
            if (course == null || course.getId() == null || course.getId() != courseId) {
                log.warn("Atlas orchestrator (batch) skipping lecture unit {}: course ownership mismatch (expected {}, got {})", id, courseId,
                        course == null ? null : course.getId());
                continue;
            }
            lectureUnits.add(lectureUnit);
        }
        return lectureUnits;
    }

    /**
     * Validates an exercise before orchestration. Returns a terminal failure result when the
     * exercise is unsupported (exam) or the chat client is missing; returns {@code null} when the
     * caller may proceed.
     */
    @Nullable
    private CompetencyOrchestrationResultDTO precheckExercise(Exercise exercise) {
        if (exercise.isExamExercise()) {
            log.info("Atlas orchestrator rejected for exam exercise {}", exercise.getId());
            return CompetencyOrchestrationResultDTO
                    .failed("Atlas orchestrator only operates on course exercises.", CompetencyOrchestrationResultDTO.FailureReason.UNSUPPORTED_EXERCISE)
                    .withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exercise.getId(), LearningObjectOutcomeDTO.Status.SKIPPED, false,
                            "Exam exercises are unsupported.")));
        }
        if (chatClient == null) {
            log.info("Atlas orchestrator requested for exercise {} but no ChatClient is available", exercise.getId());
            return CompetencyOrchestrationResultDTO.failed("Atlas chat model is not configured.", CompetencyOrchestrationResultDTO.FailureReason.NO_CHAT_CLIENT).withObjectOutcomes(
                    List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exercise.getId(), LearningObjectOutcomeDTO.Status.FAILED, true, "Chat model unavailable.")));
        }
        return null;
    }

    /**
     * Orchestrates a single exercise. Caller is responsible for holding the per-course
     * {@link #runMap} claim around all invocations within a logical run.
     */
    private CompetencyOrchestrationResultDTO orchestrateExercise(Exercise exercise, long courseId) {
        long exerciseId = exercise.getId();
        String systemPrompt;
        try {
            ExtractedContentDTO extracted = contentExtractionService.extractContent(exercise);
            if (extracted.extractedLearningText() == null || extracted.extractedLearningText().isBlank()) {
                return CompetencyOrchestrationResultDTO.noOp("No learning-relevant content in exercise.").withObjectOutcomes(List
                        .of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, LearningObjectOutcomeDTO.Status.SKIPPED, false, "No learning-relevant content.")));
            }
            List<ExerciseChange> changes = List.of(new ExerciseChange(exerciseId, extracted.title(), extracted.extractedLearningText()));
            CompetencyIndexResponseDTO competencyIndex = orchestratorPlanningToolsService.listCompetencyIndex(courseId);
            String renderedIndex = renderCompetencyIndex(competencyIndex);
            String renderedChanges = renderExerciseChangeBatch(changes);
            String renderedShortlist = renderAtlasMLShortlist(courseId, changes);
            // Map.of key order is irrelevant: the prompt template references the placeholders by
            // name, and the fence sanitization in renderExerciseChangeBatch / renderCompetencyIndex /
            // the shortlist service guarantees no user-supplied string can break out and reposition another.
            systemPrompt = templateService.render(EXECUTE_PROMPT_PATH,
                    Map.of("exerciseChanges", renderedChanges, "competencyIndex", renderedIndex, "atlasMLShortlist", renderedShortlist));
        }
        catch (Exception ex) {
            log.warn("Atlas orchestrator preparation failed for exercise {}: {}", exerciseId, ex.getMessage(), ex);
            return CompetencyOrchestrationResultDTO.failed("Atlas orchestrator run failed.", CompetencyOrchestrationResultDTO.FailureReason.INTERNAL_ERROR).withObjectOutcomes(
                    List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, LearningObjectOutcomeDTO.Status.FAILED, false, "Preparation failed.")));
        }
        // Synchronized list: Spring AI's roadmap supports parallel tool calls; the orchestrator's
        // write tools all go through OrchestratorToolHelpers.appendAction which only adds.
        List<AppliedActionDTO> appliedActions = Collections.synchronizedList(new ArrayList<>());
        OrchestrationCompletionDTO completion;
        try {
            completion = callChatClient(systemPrompt, courseId, exerciseId, appliedActions);
        }
        catch (AtlasToolCallBudget.LimitReachedException ex) {
            log.warn("Atlas orchestration tool budget exhausted for exercise {}", exerciseId);
            return toolLimitResult(appliedActions, ex.summary()).withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId,
                    appliedActions.isEmpty() ? LearningObjectOutcomeDTO.Status.FAILED : LearningObjectOutcomeDTO.Status.PARTIAL, false, "Shared tool-call limit reached.")));
        }
        catch (Exception ex) {
            log.warn("Atlas orchestrator LLM call failed for exercise {} after applying {} action(s): {}", exerciseId, appliedActions.size(), ex.getMessage(), ex);
            if (appliedActions.isEmpty()) {
                return CompetencyOrchestrationResultDTO.failed("Atlas orchestrator run failed.", CompetencyOrchestrationResultDTO.FailureReason.LLM_ERROR).withObjectOutcomes(
                        List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, LearningObjectOutcomeDTO.Status.FAILED, false, "Model execution failed.")));
            }
            return CompetencyOrchestrationResultDTO
                    .partial("Atlas orchestrator run failed after applying " + appliedActions.size() + " action(s).", List.copyOf(appliedActions),
                            CompetencyOrchestrationResultDTO.FailureReason.LLM_ERROR)
                    .withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, LearningObjectOutcomeDTO.Status.PARTIAL, false,
                            "Mutation committed before verification failed.")));
        }
        log.info("Atlas orchestrator completed for exercise {} (course {}) with {} applied action(s)", exerciseId, courseId, appliedActions.size());
        CompetencyOrchestrationResultDTO result = resultFromCompletion(completion, appliedActions);
        LearningObjectOutcomeDTO.Status objectStatus = result.status() == CompetencyOrchestrationResultDTO.Status.PARTIAL ? LearningObjectOutcomeDTO.Status.PARTIAL
                : result.status() == CompetencyOrchestrationResultDTO.Status.FAILED ? LearningObjectOutcomeDTO.Status.FAILED : LearningObjectOutcomeDTO.Status.PROCESSED;
        return result.withObjectOutcomes(List.of(outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exerciseId, objectStatus, false, "Single-object orchestration completed.")));
    }

    /**
     * Orchestrates a batch of exercises and lecture units in one LLM call. All are extracted and
     * rendered into a single numbered change batch; the prompt already reasons across multiple entries.
     * Caller is responsible for holding the per-course {@link #runMap} claim.
     */
    private CompetencyOrchestrationResultDTO orchestrateBatch(List<Exercise> exercises, List<LectureUnit> lectureUnits, long courseId,
            List<LearningObjectOutcomeDTO> initialOutcomes) {
        Map<LearningObjectKey, LearningObjectOutcomeDTO> outcomes = outcomeMap(initialOutcomes);
        List<LearningObjectKey> processedCandidates = new ArrayList<>();
        List<ExerciseChange> exerciseChanges = new ArrayList<>();
        List<LectureUnitChange> lectureUnitChanges = new ArrayList<>();
        String systemPrompt;
        try {
            for (Exercise exercise : exercises) {
                LearningObjectKey key = new LearningObjectKey(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exercise.getId());
                try {
                    ExtractedContentDTO extracted = contentExtractionService.extractContent(exercise);
                    if (extracted.extractedLearningText() == null || extracted.extractedLearningText().isBlank()) {
                        outcomes.put(key, outcome(key.objectType(), key.objectId(), LearningObjectOutcomeDTO.Status.SKIPPED, false, "No learning-relevant content."));
                        continue;
                    }
                    exerciseChanges.add(new ExerciseChange(exercise.getId(), extracted.title(), extracted.extractedLearningText()));
                    processedCandidates.add(key);
                }
                catch (Exception ex) {
                    outcomes.put(key, outcome(key.objectType(), key.objectId(), LearningObjectOutcomeDTO.Status.FAILED, true, "Content extraction failed."));
                    log.warn("Atlas orchestrator (batch) skipping exercise {} for course {}: {}", exercise.getId(), courseId, ex.getMessage(), ex);
                }
            }
            for (LectureUnit lectureUnit : lectureUnits) {
                LearningObjectKey key = new LearningObjectKey(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnit.getId());
                try {
                    ExtractedContentDTO extracted = contentExtractionService.extractContent(lectureUnit);
                    if (extracted.extractedLearningText() == null || extracted.extractedLearningText().isBlank()) {
                        outcomes.put(key, outcome(key.objectType(), key.objectId(), LearningObjectOutcomeDTO.Status.SKIPPED, false, "No learning-relevant content."));
                        continue;
                    }
                    lectureUnitChanges.add(new LectureUnitChange(lectureUnit.getId(), extracted.title(), extracted.extractedLearningText()));
                    processedCandidates.add(key);
                }
                catch (Exception ex) {
                    outcomes.put(key, outcome(key.objectType(), key.objectId(), LearningObjectOutcomeDTO.Status.FAILED, true, "Content extraction failed."));
                    log.warn("Atlas orchestrator (batch) skipping lecture unit {} for course {}: {}", lectureUnit.getId(), courseId, ex.getMessage(), ex);
                }
            }
            if (exerciseChanges.isEmpty() && lectureUnitChanges.isEmpty()) {
                boolean extractionFailed = outcomes.values().stream().anyMatch(outcome -> outcome.status() == LearningObjectOutcomeDTO.Status.FAILED);
                CompetencyOrchestrationResultDTO result = extractionFailed
                        ? CompetencyOrchestrationResultDTO.failed("Atlas orchestrator run failed.", CompetencyOrchestrationResultDTO.FailureReason.INTERNAL_ERROR)
                        : CompetencyOrchestrationResultDTO.noOp("No learning-relevant content in batch.");
                return attachBatchOutcomes(result, outcomes, processedCandidates);
            }
            CompetencyIndexResponseDTO competencyIndex = orchestratorPlanningToolsService.listCompetencyIndex(courseId);
            String renderedIndex = renderCompetencyIndex(competencyIndex);
            String renderedChanges = renderChangeBatch(exerciseChanges, lectureUnitChanges);
            String renderedShortlist = renderAtlasMLShortlist(courseId, exerciseChanges);
            systemPrompt = templateService.render(EXECUTE_PROMPT_PATH,
                    Map.of("exerciseChanges", renderedChanges, "competencyIndex", renderedIndex, "atlasMLShortlist", renderedShortlist));
        }
        catch (Exception ex) {
            log.warn("Atlas orchestrator (batch) preparation failed for course {}: {}", courseId, ex.getMessage(), ex);
            return attachBatchOutcomes(CompetencyOrchestrationResultDTO.failed("Atlas orchestrator run failed.", CompetencyOrchestrationResultDTO.FailureReason.INTERNAL_ERROR),
                    outcomes, processedCandidates);
        }
        List<AppliedActionDTO> appliedActions = Collections.synchronizedList(new ArrayList<>());
        OrchestrationCompletionDTO completion;
        long anchorId = exerciseChanges.isEmpty() ? lectureUnitChanges.getFirst().lectureUnitId() : exerciseChanges.getFirst().exerciseId();
        try {
            completion = callChatClient(systemPrompt, courseId, anchorId, appliedActions);
        }
        catch (AtlasToolCallBudget.LimitReachedException ex) {
            log.warn("Atlas orchestration tool budget exhausted for course {}", courseId);
            return attachBatchOutcomes(toolLimitResult(appliedActions, ex.summary()), outcomes, processedCandidates);
        }
        catch (Exception ex) {
            log.warn("Atlas orchestrator (batch) LLM call failed for course {} after applying {} action(s): {}", courseId, appliedActions.size(), ex.getMessage(), ex);
            CompetencyOrchestrationResultDTO result = appliedActions.isEmpty()
                    ? CompetencyOrchestrationResultDTO.failed("Atlas orchestrator run failed.", CompetencyOrchestrationResultDTO.FailureReason.LLM_ERROR)
                    : CompetencyOrchestrationResultDTO.partial("Atlas orchestrator run failed after applying " + appliedActions.size() + " action(s).", List.copyOf(appliedActions),
                            CompetencyOrchestrationResultDTO.FailureReason.LLM_ERROR);
            return attachBatchOutcomes(result, outcomes, processedCandidates);
        }
        log.info("Atlas orchestrator (batch) completed for course {} over {} exercise(s) and {} lecture unit(s) with {} applied action(s)", courseId, exerciseChanges.size(),
                lectureUnitChanges.size(), appliedActions.size());
        return attachBatchOutcomes(resultFromCompletion(completion, appliedActions), outcomes, processedCandidates);
    }

    /** Requeues only untouched objects whose recorded outcome explicitly permits retry. */
    private void requeueRetryEligible(long courseId, List<LearningObjectOutcomeDTO> outcomes) {
        Set<Long> exerciseIds = retryEligibleIds(outcomes, LearningObjectOutcomeDTO.ObjectType.EXERCISE);
        Set<Long> lectureUnitIds = retryEligibleIds(outcomes, LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT);
        if (exerciseIds.isEmpty() && lectureUnitIds.isEmpty()) {
            return;
        }
        try {
            contentChangeAccumulatorService.requeueAfterFailedRun(courseId, exerciseIds, lectureUnitIds);
        }
        catch (RuntimeException ex) {
            log.warn("Atlas orchestrator could not requeue retry-eligible content for course {}: {}", courseId, ex.getMessage(), ex);
        }
    }

    private static CompetencyOrchestrationResultDTO attachBatchOutcomes(CompetencyOrchestrationResultDTO result, Map<LearningObjectKey, LearningObjectOutcomeDTO> outcomes,
            List<LearningObjectKey> processedCandidates) {
        LearningObjectOutcomeDTO.Status objectStatus;
        boolean retryEligible;
        String detail;
        switch (result.status()) {
            case SUCCESS, NO_OP -> {
                objectStatus = LearningObjectOutcomeDTO.Status.PROCESSED;
                retryEligible = false;
                detail = "Included in a verified orchestration decision.";
            }
            case PARTIAL -> {
                objectStatus = LearningObjectOutcomeDTO.Status.PARTIAL;
                retryEligible = false;
                detail = "Shared run committed a mutation before verification failed.";
            }
            case FAILED -> {
                objectStatus = LearningObjectOutcomeDTO.Status.FAILED;
                retryEligible = true;
                detail = "Run failed before a verified terminal decision.";
            }
            case IN_PROGRESS -> {
                objectStatus = LearningObjectOutcomeDTO.Status.DEFERRED;
                retryEligible = true;
                detail = "Another course run is active.";
            }
            default -> throw new IllegalStateException("Unsupported orchestration status: " + result.status());
        }
        for (LearningObjectKey key : processedCandidates) {
            outcomes.put(key, outcome(key.objectType(), key.objectId(), objectStatus, retryEligible, detail));
        }
        if (result.failureReason() == CompetencyOrchestrationResultDTO.FailureReason.TOOL_CALL_LIMIT_EXCEEDED) {
            outcomes.replaceAll((key, value) -> new LearningObjectOutcomeDTO(value.objectType(), value.objectId(), value.status(), false, value.detail()));
        }
        return result.withObjectOutcomes(sortedOutcomes(outcomes.values()));
    }

    private static List<LearningObjectOutcomeDTO> skippedResolutionOutcomes(Set<Long> claimedExerciseIds, Set<Long> claimedLectureUnitIds, List<Exercise> exercises,
            List<LectureUnit> lectureUnits) {
        Set<Long> resolvedExerciseIds = exercises.stream().map(Exercise::getId).collect(Collectors.toSet());
        Set<Long> resolvedLectureUnitIds = lectureUnits.stream().map(LectureUnit::getId).collect(Collectors.toSet());
        List<LearningObjectOutcomeDTO> outcomes = new ArrayList<>();
        claimedExerciseIds.stream().filter(id -> !resolvedExerciseIds.contains(id)).sorted().map(id -> outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, id,
                LearningObjectOutcomeDTO.Status.SKIPPED, false, "Unavailable, unsupported, or outside the active course.")).forEach(outcomes::add);
        claimedLectureUnitIds.stream().filter(id -> !resolvedLectureUnitIds.contains(id)).sorted().map(id -> outcome(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, id,
                LearningObjectOutcomeDTO.Status.SKIPPED, false, "Unavailable, unsupported, or outside the active course.")).forEach(outcomes::add);
        return List.copyOf(outcomes);
    }

    private static List<LearningObjectOutcomeDTO> appendResolvedOutcomes(List<LearningObjectOutcomeDTO> initialOutcomes, List<Exercise> exercises, List<LectureUnit> lectureUnits,
            LearningObjectOutcomeDTO.Status status, boolean retryEligible, String detail) {
        Map<LearningObjectKey, LearningObjectOutcomeDTO> outcomes = outcomeMap(initialOutcomes);
        exercises.forEach(exercise -> outcomes.put(new LearningObjectKey(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exercise.getId()),
                outcome(LearningObjectOutcomeDTO.ObjectType.EXERCISE, exercise.getId(), status, retryEligible, detail)));
        lectureUnits.forEach(lectureUnit -> outcomes.put(new LearningObjectKey(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnit.getId()),
                outcome(LearningObjectOutcomeDTO.ObjectType.LECTURE_UNIT, lectureUnit.getId(), status, retryEligible, detail)));
        return sortedOutcomes(outcomes.values());
    }

    private static Map<LearningObjectKey, LearningObjectOutcomeDTO> outcomeMap(Collection<LearningObjectOutcomeDTO> outcomes) {
        Map<LearningObjectKey, LearningObjectOutcomeDTO> result = new LinkedHashMap<>();
        outcomes.forEach(outcome -> result.put(new LearningObjectKey(outcome.objectType(), outcome.objectId()), outcome));
        return result;
    }

    private static List<LearningObjectOutcomeDTO> sortedOutcomes(Collection<LearningObjectOutcomeDTO> outcomes) {
        return outcomes.stream().sorted(Comparator.comparing(LearningObjectOutcomeDTO::objectType).thenComparingLong(LearningObjectOutcomeDTO::objectId)).toList();
    }

    private static Set<Long> retryEligibleIds(List<LearningObjectOutcomeDTO> outcomes, LearningObjectOutcomeDTO.ObjectType objectType) {
        return outcomes.stream().filter(LearningObjectOutcomeDTO::retryEligible).filter(outcome -> outcome.objectType() == objectType).map(LearningObjectOutcomeDTO::objectId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static LearningObjectOutcomeDTO outcome(LearningObjectOutcomeDTO.ObjectType objectType, long objectId, LearningObjectOutcomeDTO.Status status, boolean retryEligible,
            String detail) {
        return new LearningObjectOutcomeDTO(objectType, objectId, status, retryEligible, detail);
    }

    private record LearningObjectKey(LearningObjectOutcomeDTO.ObjectType objectType, long objectId) {
    }

    /**
     * Drive the Spring AI tool-calling loop through the shared {@link AtlasAgentDelegationService}
     * harness. {@link #run(long)} / {@link #runBatch(long, Set)} guarantee {@link #chatClient} is
     * non-null before we get here, so the harness short-circuit never trips. Returns the (possibly
     * empty) final assistant message; the orchestrator's mutations have already been appended to
     * {@code appliedActions} via the typed {@link AppliedActionsBuffer} in the tool context (passed by
     * reference into the harness).
     * <p>
     * The orchestrator owns its model config: the orchestrator deployment / temperature / reasoning
     * effort from {@link AtlasOrchestratorProperties} are applied via {@link #buildChatOptions()} and
     * handed to the harness, which performs the call with chat memory OFF (each run is a fresh call).
     * The {@link ChatResponse} (rather than just its content) is returned so the round's token usage
     * is persisted via {@link LLMTokenUsageService}, feeding the existing per-course LLM cost views.
     * Tracking is best-effort: it never throws, and {@code userId} resolves to {@code null} when there
     * is no {@code SecurityContext} (e.g. a scheduler-driven run).
     */
    private OrchestrationCompletionDTO callChatClient(String systemPrompt, long courseId, long exerciseId, List<AppliedActionDTO> appliedActions) {
        OpenAiChatOptions.Builder options = buildChatOptions();
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(OrchestratorToolContextKeys.COURSE_ID_KEY, courseId);
        toolContext.put(OrchestratorToolContextKeys.APPLIED_ACTIONS_KEY, new AppliedActionsBuffer(appliedActions));
        toolContext.put(OrchestratorToolContextKeys.LEARNING_OBJECT_ID_KEY, exerciseId);
        toolContext.put(OrchestratorToolContextKeys.TOOL_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        toolContext.put(OrchestratorToolContextKeys.LAST_INDEX_READ_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        toolContext.put(OrchestratorToolContextKeys.LAST_DELEGATION_SEQUENCE_KEY, OrchestratorToolContextKeys.newSequenceHolder());
        AtomicReference<OrchestrationCompletionDTO> completionHolder = OrchestratorToolContextKeys.newOrchestrationCompletionHolder();
        toolContext.put(OrchestratorToolContextKeys.ORCHESTRATION_COMPLETION_KEY, completionHolder);
        ChatResponse chatResponse = delegationService.delegateOrchestratorRound(systemPrompt, "Plan, delegate, verify, optionally correct once, then call completeOrchestration.",
                options, toolContext, orchestratorReadToolCallbackProvider, orchestratorPlanningToolCallbackProvider, orchestratorDelegationToolCallbackProvider,
                orchestratorTerminalToolCallbackProvider);
        Long userId = SecurityUtils.getCurrentUserLogin().flatMap(userRepository::findIdByLogin).orElse(null);
        llmTokenUsageService.trackChatResponseTokenUsage(chatResponse, LLMServiceType.ATLAS, ORCHESTRATION_PIPELINE_ID,
                builder -> builder.withCourse(courseId).withExercise(exerciseId).withUser(userId));
        AtlasToolCallBudget.checkResponse(chatResponse, toolContext);
        OrchestrationCompletionDTO completion = completionHolder.get();
        if (completion == null) {
            throw new IllegalStateException("Main orchestrator returned without calling completeOrchestration.");
        }
        return completion;
    }

    /** Keeps committed actions and identifies budget exhaustion as a terminal failure. */
    private static CompetencyOrchestrationResultDTO toolLimitResult(List<AppliedActionDTO> actions, String message) {
        return actions.isEmpty() ? CompetencyOrchestrationResultDTO.failed(message, CompetencyOrchestrationResultDTO.FailureReason.TOOL_CALL_LIMIT_EXCEEDED)
                : CompetencyOrchestrationResultDTO.partial(message, List.copyOf(actions), CompetencyOrchestrationResultDTO.FailureReason.TOOL_CALL_LIMIT_EXCEEDED);
    }

    private static CompetencyOrchestrationResultDTO resultFromCompletion(OrchestrationCompletionDTO completion, List<AppliedActionDTO> appliedActions) {
        List<AppliedActionDTO> actions = List.copyOf(appliedActions);
        if (completion.verified()) {
            return actions.isEmpty() ? CompetencyOrchestrationResultDTO.noOp(completion.message()) : CompetencyOrchestrationResultDTO.success(completion.message(), actions);
        }
        return actions.isEmpty() ? CompetencyOrchestrationResultDTO.failed(completion.message(), CompetencyOrchestrationResultDTO.FailureReason.LLM_ERROR)
                : CompetencyOrchestrationResultDTO.partial(completion.message(), actions, CompetencyOrchestrationResultDTO.FailureReason.LLM_ERROR);
    }

    /** GPT-5 reasoning models reject explicit temperature alongside reasoningEffort, so we omit one when the other is set. */
    private OpenAiChatOptions.Builder buildChatOptions() {
        var builder = OpenAiChatOptions.builder().deploymentName(deploymentName);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.reasoningEffort(reasoningEffort);
        }
        else {
            builder.temperature(temperature);
        }
        return builder;
    }

    private static String renderExerciseChangeBatch(List<ExerciseChange> changes) {
        return renderChangeBatch(changes, List.of());
    }

    /**
     * Renders the combined change batch: exercise entries ({@code [UPDATE exercise id=..]}) first, then
     * lecture-unit entries ({@code [UPDATE lecture-unit id=..]}), sharing one continuous 1-based numbering
     * so the LLM sees a single ordered list. All instructor text is fence-sanitized and length-capped.
     */
    private static String renderChangeBatch(List<ExerciseChange> exerciseChanges, List<LectureUnitChange> lectureUnitChanges) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (ExerciseChange change : exerciseChanges) {
            String safeTitle = sanitizeForPrompt(change.title(), EXERCISE_TITLE_MAX);
            String safeBody = change.problemStatement() == null || change.problemStatement().isBlank() ? "(no problem statement available)"
                    : sanitizeForPrompt(change.problemStatement(), PROBLEM_STATEMENT_MAX);
            if (index > 1) {
                sb.append("\n\n");
            }
            sb.append(index).append(". [UPDATE exercise id=").append(change.exerciseId()).append("] ").append(safeTitle).append('\n').append(safeBody);
            index++;
        }
        for (LectureUnitChange change : lectureUnitChanges) {
            String safeTitle = sanitizeForPrompt(change.title(), LECTURE_UNIT_NAME_MAX);
            String safeBody = change.learningText() == null || change.learningText().isBlank() ? "(no learning text available)"
                    : sanitizeForPrompt(change.learningText(), PROBLEM_STATEMENT_MAX);
            if (index > 1) {
                sb.append("\n\n");
            }
            sb.append(index).append(". [UPDATE lecture-unit id=").append(change.lectureUnitId()).append("] ").append(safeTitle).append('\n').append(safeBody);
            index++;
        }
        return sb.toString();
    }

    /** One extracted exercise change rendered as a numbered entry in the change batch block. */
    private record ExerciseChange(long exerciseId, String title, @Nullable String problemStatement) {
    }

    /** One extracted lecture-unit change rendered as a numbered entry in the change batch block. */
    private record LectureUnitChange(long lectureUnitId, String title, @Nullable String learningText) {
    }

    /**
     * Fetches and renders the per-exercise AtlasML similarity shortlist for the batch. The cleaned learning
     * text of each change is the AtlasML query; the result is the injection-safe block interpolated into the
     * execute prompt. Best-effort: an unavailable or failing AtlasML yields an omitted block (see {@link AtlasMLShortlistService}).
     * The whole path is guarded here so an unexpected shortlist failure can never abort the surrounding
     * orchestration-preparation try with an INTERNAL_ERROR — the section is simply dropped.
     */
    private String renderAtlasMLShortlist(long courseId, List<ExerciseChange> changes) {
        try {
            List<AtlasMLShortlistService.ExerciseExtract> extracts = changes.stream()
                    .map(change -> new AtlasMLShortlistService.ExerciseExtract(change.exerciseId(), change.problemStatement())).toList();
            return shortlistService.renderShortlist(shortlistService.fetchShortlists(courseId, extracts));
        }
        catch (Exception ex) {
            log.debug("AtlasML shortlist generation failed for course {}; continuing without shortlist: {}", courseId, ex.getMessage(), ex);
            return "";
        }
    }

    /**
     * Neutralizes instructor text before prompt interpolation: strips control / zero-width
     * characters, neutralizes the user-data fence delimiters, and hard-truncates at {@code maxChars}
     * (never mid surrogate pair). Preserves {@code \n}/{@code \t} for the multi-line execute-prompt body.
     */
    static String sanitizeForPrompt(@Nullable String raw, int maxChars) {
        return AtlasPromptSanitizer.sanitizeForPrompt(raw, maxChars, false, "(empty)");
    }

    private static String renderCompetencyIndex(CompetencyIndexResponseDTO index) {
        List<CompetencyIndexDTO> competencies = index.competencies();
        List<CompetencyIndexResponseDTO.UnassignedExerciseRefDTO> unassigned = index.unassignedExercises();
        StringBuilder sb = new StringBuilder();
        if (competencies.isEmpty()) {
            sb.append("(no competencies defined in this course yet)\n");
        }
        else {
            for (int i = 0; i < competencies.size(); i++) {
                boolean lastCompetency = i == competencies.size() - 1;
                appendCompetencyBranch(sb, competencies.get(i), lastCompetency);
            }
        }
        sb.append('\n').append("UNASSIGNED EXERCISES (currently linked to no competency):\n");
        if (unassigned.isEmpty()) {
            sb.append("(all course exercises are linked to at least one competency)");
        }
        else {
            for (int i = 0; i < unassigned.size(); i++) {
                boolean last = i == unassigned.size() - 1;
                sb.append(last ? "└── " : "├── ").append(formatUnassignedLine(unassigned.get(i))).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String formatUnassignedLine(CompetencyIndexResponseDTO.UnassignedExerciseRefDTO exercise) {
        String title = sanitizeForPrompt(Objects.requireNonNullElse(exercise.title(), "(untitled)"), EXERCISE_TITLE_MAX);
        String type = sanitizeForPrompt(Objects.requireNonNullElse(exercise.type(), "unknown"), TYPE_LABEL_MAX);
        return "[" + exercise.id() + "] " + title + " (" + type + ")";
    }

    private static void appendCompetencyBranch(StringBuilder sb, CompetencyIndexDTO entry, boolean lastCompetency) {
        String taxonomy = entry.taxonomy() != null ? entry.taxonomy().name() : "UNSPECIFIED";
        String safeTitle = sanitizeForPrompt(entry.title(), COMPETENCY_TITLE_MAX);
        sb.append(lastCompetency ? "└── " : "├── ").append('[').append(entry.id()).append("] ").append(safeTitle).append(" (").append(entry.type()).append(", ").append(taxonomy)
                .append(")\n");
        String childIndent = lastCompetency ? "    " : "│   ";
        boolean hasLectureUnits = !entry.lectureUnits().isEmpty();
        boolean hasRelations = !entry.relations().isEmpty();
        List<String> exerciseLines = entry.exercises().stream().map(CompetencyOrchestrationService::formatExerciseLine).toList();
        appendLeafGroup(sb, childIndent, "exercises", exerciseLines, !hasLectureUnits && !hasRelations);
        if (hasLectureUnits) {
            List<String> lectureUnitLines = entry.lectureUnits().stream().map(CompetencyOrchestrationService::formatLectureUnitLine).toList();
            appendLeafGroup(sb, childIndent, "lecture units", lectureUnitLines, !hasRelations);
        }
        if (hasRelations) {
            List<String> relationLines = entry.relations().stream().map(CompetencyOrchestrationService::formatRelationLine).toList();
            appendLeafGroup(sb, childIndent, "relations", relationLines, true);
        }
    }

    private static String formatExerciseLine(CompetencyIndexDTO.ExerciseLinkRefDTO exercise) {
        String safeTitle = sanitizeForPrompt(exercise.title(), EXERCISE_TITLE_MAX);
        String safeType = sanitizeForPrompt(Objects.requireNonNullElse(exercise.type(), "unknown"), TYPE_LABEL_MAX);
        String provenance = "generatedByAi=" + exercise.generatedByAi();
        if (exercise.weight() == null) {
            return safeTitle + " (" + safeType + ", " + provenance + ")";
        }
        return safeTitle + " (" + safeType + ", w=" + String.format(Locale.ROOT, "%.1f", exercise.weight()) + ", " + provenance + ")";
    }

    private static String formatLectureUnitLine(CompetencyIndexDTO.LectureUnitRefDTO lectureUnit) {
        String safeName = sanitizeForPrompt(lectureUnit.name(), LECTURE_UNIT_NAME_MAX);
        String safeType = sanitizeForPrompt(Objects.requireNonNullElse(lectureUnit.type(), "unknown"), TYPE_LABEL_MAX);
        return safeName + " (" + safeType + ", generatedByAi=" + lectureUnit.generatedByAi() + ")";
    }

    private static String formatRelationLine(CompetencyIndexDTO.RelationRefDTO relation) {
        return relation.tailCompetencyId() + " --" + relation.relationType() + "--> " + relation.headCompetencyId();
    }

    /** Distributed map entry guarding per-course runs; expired via {@link #RUN_LEASE} in {@link #claimRun}. */
    record RunInfo(String runId, long exerciseId, @Nullable Instant startedAt) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static void appendLeafGroup(StringBuilder sb, String parentIndent, String label, List<String> items, boolean lastGroup) {
        String groupBranch = lastGroup ? "└── " : "├── ";
        sb.append(parentIndent).append(groupBranch).append(label);
        if (items.isEmpty()) {
            sb.append(": —\n");
            return;
        }
        sb.append('\n');
        String leafIndent = parentIndent + (lastGroup ? "    " : "│   ");
        for (int i = 0; i < items.size(); i++) {
            boolean lastItem = i == items.size() - 1;
            sb.append(leafIndent).append(lastItem ? "└── " : "├── ").append(items.get(i)).append('\n');
        }
    }
}

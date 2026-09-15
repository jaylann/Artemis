package de.tum.cit.aet.artemis.atlas.service;

import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.courseIdFromContext;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.exerciseType;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.missingCourseContextError;
import static de.tum.cit.aet.artemis.atlas.service.OrchestratorToolHelpers.toJson;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.atlas.config.AtlasEnabled;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyExerciseLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyLectureUnitLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyRelation;
import de.tum.cit.aet.artemis.atlas.domain.competency.CourseCompetency;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyIndexDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyIndexResponseDTO;
import de.tum.cit.aet.artemis.atlas.repository.CompetencyRelationRepository;
import de.tum.cit.aet.artemis.atlas.repository.CourseCompetencyRepository;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;

/**
 * Planning orchestrator tool: lists the whole competency index for the current course so the LLM can
 * plan the batch of competency-management actions. Split from the former monolithic orchestrator
 * tools service so the batch-planning read is registered as its own
 * {@link org.springframework.ai.tool.ToolCallbackProvider} bean, distinct from the narrow-scope read
 * tools ({@link OrchestratorReadToolsService}).
 * <p>
 * The tool is course-scoped through the Spring AI {@link ToolContext}. The public {@code long}
 * overload of {@link #listCompetencyIndex(long)} is also called directly by
 * {@link CompetencyOrchestrationService} to seed the system prompt and save a tool-call round-trip.
 */
@Lazy
@Service
@Conditional(AtlasEnabled.class)
public class OrchestratorPlanningToolsService {

    private final ObjectMapper objectMapper;

    private final CourseCompetencyRepository courseCompetencyRepository;

    private final ExerciseRepository exerciseRepository;

    private final CompetencyRelationRepository competencyRelationRepository;

    /**
     * Creates the planning tools service.
     *
     * @param objectMapper                 JSON serialiser for tool responses
     * @param courseCompetencyRepository   repository for competency lookups
     * @param exerciseRepository           repository for exercise lookups
     * @param competencyRelationRepository repository for course-scoped relation references
     */
    public OrchestratorPlanningToolsService(ObjectMapper objectMapper, CourseCompetencyRepository courseCompetencyRepository, ExerciseRepository exerciseRepository,
            CompetencyRelationRepository competencyRelationRepository) {
        this.objectMapper = objectMapper;
        this.courseCompetencyRepository = courseCompetencyRepository;
        this.exerciseRepository = exerciseRepository;
        this.competencyRelationRepository = competencyRelationRepository;
    }

    /**
     * LLM tool: returns the competency index for the current course as JSON.
     *
     * @param toolContext carries the current course id
     * @return the JSON-serialized index, or a JSON error
     */
    @Tool(description = "List the competency index for the current course. Returns two sections: (1) competencies — id, title, taxonomy, type (competency or prerequisite), "
            + "linked exercises (with title, exercise type, current link weight — 1.0 / 0.5 / 0.3, and generatedByAi provenance) and linked lecture units (with name, lecture-unit type, "
            + "and generatedByAi provenance), plus directed relation references as tailCompetencyId -- relationType --> headCompetencyId. "
            + "generatedByAi=false means the instructor created the link; true means the orchestrator created it. "
            + "(2) unassignedExercises — exercises in the course that are currently not linked to any competency (id, title, type), provided as background only; act on the changed batch, not unrelated coverage gaps. "
            + "The initial index is already provided in the system prompt; call this again after any CREATE / DELETE so subsequent actions reference up-to-date ids.")
    public String listCompetencyIndex(ToolContext toolContext) {
        Long courseId = courseIdFromContext(toolContext);
        if (courseId == null) {
            return missingCourseContextError(objectMapper);
        }
        OrchestratorToolHelpers.markIndexRead(toolContext);
        return toJson(objectMapper, listCompetencyIndex(courseId));
    }

    /**
     * Builds the competency index so callers can seed the system prompt and save a tool-call round-trip.
     *
     * @param courseId the course
     * @return the index
     */
    public CompetencyIndexResponseDTO listCompetencyIndex(long courseId) {
        Set<CourseCompetency> competencies = courseCompetencyRepository.findAllForCourseWithExercisesAndLectureUnitsAndLecturesAndAttachments(courseId);
        Set<CompetencyRelation> relations = competencyRelationRepository.findAllWithHeadAndTailByCourseId(courseId);
        List<CompetencyIndexDTO> entries = competencies.stream().map(competency -> toIndexEntry(competency, relations)).sorted(Comparator.comparing(CompetencyIndexDTO::id))
                .toList();
        Set<Long> linkedExerciseIds = competencies.stream().flatMap(c -> c.getExerciseLinks().stream()).map(CompetencyExerciseLink::getExercise).map(Exercise::getId)
                .collect(Collectors.toSet());
        // findAllExercisesByCourseId already filters by `e.course.id`, so exam exercises are excluded.
        List<CompetencyIndexResponseDTO.UnassignedExerciseRefDTO> unassigned = exerciseRepository.findAllExercisesByCourseId(courseId).stream()
                .filter(exercise -> !linkedExerciseIds.contains(exercise.getId()))
                .map(exercise -> new CompetencyIndexResponseDTO.UnassignedExerciseRefDTO(exercise.getId(), exercise.getTitle(), exerciseType(exercise)))
                .sorted(Comparator.comparing(CompetencyIndexResponseDTO.UnassignedExerciseRefDTO::id)).toList();
        return new CompetencyIndexResponseDTO(entries, unassigned);
    }

    /**
     * Projects a competency onto its index entry. Linked exercises are ordered by exercise id and lecture units by
     * name, so the rendered index the LLM sees is stable across runs — an unstable ordering would change the prompt
     * (and therefore the model's plan) for an unchanged course. Lecture units without a name are dropped, mirroring
     * the equivalent projection in {@link OrchestratorReadToolsService} so both tools expose the same set.
     *
     * @param competency the competency to project, with exercise and lecture-unit links already fetched
     * @return the index entry for this competency
     */
    private static CompetencyIndexDTO toIndexEntry(CourseCompetency competency, Set<CompetencyRelation> courseRelations) {
        List<CompetencyIndexDTO.ExerciseLinkRefDTO> exercises = competency.getExerciseLinks().stream()
                .sorted(Comparator.comparing((CompetencyExerciseLink link) -> link.getExercise().getId()))
                .map(link -> new CompetencyIndexDTO.ExerciseLinkRefDTO(link.getExercise().getTitle(), exerciseType(link.getExercise()), link.getWeight(), link.isGeneratedByAi()))
                .toList();
        List<CompetencyIndexDTO.LectureUnitRefDTO> lectureUnits = competency.getLectureUnitLinks().stream().filter(link -> link.getLectureUnit().getName() != null)
                .sorted(Comparator.comparing((CompetencyLectureUnitLink link) -> link.getLectureUnit().getName()))
                .map(link -> new CompetencyIndexDTO.LectureUnitRefDTO(link.getLectureUnit().getName(), link.getLectureUnit().getType(), link.isGeneratedByAi())).toList();
        List<CompetencyIndexDTO.RelationRefDTO> relations = courseRelations.stream()
                .filter(relation -> relation.getTailCompetency().getId().equals(competency.getId()) || relation.getHeadCompetency().getId().equals(competency.getId()))
                .map(relation -> new CompetencyIndexDTO.RelationRefDTO(relation.getTailCompetency().getId(), relation.getHeadCompetency().getId(), relation.getType()))
                .sorted(Comparator.comparingLong(CompetencyIndexDTO.RelationRefDTO::tailCompetencyId).thenComparingLong(CompetencyIndexDTO.RelationRefDTO::headCompetencyId)
                        .thenComparing(CompetencyIndexDTO.RelationRefDTO::relationType))
                .toList();
        return new CompetencyIndexDTO(competency.getId(), competency.getTitle(), competency.getTaxonomy(), competency.getType(), exercises, lectureUnits, relations);
    }
}

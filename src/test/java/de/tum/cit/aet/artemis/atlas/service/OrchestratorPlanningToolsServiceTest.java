package de.tum.cit.aet.artemis.atlas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.cit.aet.artemis.atlas.domain.competency.Competency;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyExerciseLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyLectureUnitLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyRelation;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyTaxonomy;
import de.tum.cit.aet.artemis.atlas.domain.competency.CourseCompetency;
import de.tum.cit.aet.artemis.atlas.domain.competency.RelationType;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyIndexDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyIndexResponseDTO;
import de.tum.cit.aet.artemis.atlas.repository.CompetencyRelationRepository;
import de.tum.cit.aet.artemis.atlas.repository.CourseCompetencyRepository;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseTestRepository;
import de.tum.cit.aet.artemis.lecture.domain.Lecture;
import de.tum.cit.aet.artemis.lecture.domain.TextUnit;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;

/** Unit tests for {@link OrchestratorPlanningToolsService}; relocated from the former monolithic OrchestratorToolsServiceTest. */
@ExtendWith(MockitoExtension.class)
class OrchestratorPlanningToolsServiceTest {

    private static final long COURSE_ID = 42L;

    @Mock
    private CourseCompetencyRepository courseCompetencyRepository;

    @Mock
    private ExerciseTestRepository exerciseRepository;

    @Mock
    private CompetencyRelationRepository competencyRelationRepository;

    private OrchestratorPlanningToolsService service;

    @BeforeEach
    void setUp() {
        service = new OrchestratorPlanningToolsService(new ObjectMapper(), courseCompetencyRepository, exerciseRepository, competencyRelationRepository);
    }

    @Test
    void listCompetencyIndex_includesCurrentLinkWeights() {
        Course course = courseWithId(COURSE_ID);
        CourseCompetency competency = newCompetency(5L, "Algorithms and Complexity", "Desc", CompetencyTaxonomy.APPLY, course);
        ProgrammingExercise partial = exerciseInCourse(20L, "Hash Maps in Practice", course);
        ProgrammingExercise standalone = exerciseInCourse(21L, "Sorting Fundamentals", course);
        // Inserted in reverse id order so the containsExactly assertion below proves the service sorts by exercise id
        // rather than merely preserving the fixture's insertion order.
        Set<CompetencyExerciseLink> links = new LinkedHashSet<>();
        links.add(new CompetencyExerciseLink(competency, standalone, 1.0));
        links.add(new CompetencyExerciseLink(competency, partial, 0.5));
        links.iterator().next().setGeneratedByAi(true);
        competency.setExerciseLinks(links);
        TextUnit instructorLecture = lectureUnitInCourse(30L, "Sorting lecture", course);
        TextUnit generatedLecture = lectureUnitInCourse(31L, "Hash maps lecture", course);
        CompetencyLectureUnitLink instructorLectureLink = new CompetencyLectureUnitLink(competency, instructorLecture, 1.0);
        CompetencyLectureUnitLink generatedLectureLink = new CompetencyLectureUnitLink(competency, generatedLecture, 0.5);
        generatedLectureLink.setGeneratedByAi(true);
        competency.setLectureUnitLinks(new LinkedHashSet<>(Set.of(instructorLectureLink, generatedLectureLink)));
        when(courseCompetencyRepository.findAllForCourseWithExercisesAndLectureUnitsAndLecturesAndAttachments(COURSE_ID)).thenReturn(Set.of(competency));
        when(exerciseRepository.findAllExercisesByCourseId(COURSE_ID)).thenReturn(Set.of(partial, standalone));

        CompetencyIndexResponseDTO index = service.listCompetencyIndex(COURSE_ID);

        assertThat(index.competencies()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(5L);
            assertThat(entry.exercises())
                    .extracting(CompetencyIndexDTO.ExerciseLinkRefDTO::title, CompetencyIndexDTO.ExerciseLinkRefDTO::type, CompetencyIndexDTO.ExerciseLinkRefDTO::weight,
                            CompetencyIndexDTO.ExerciseLinkRefDTO::generatedByAi)
                    .containsExactly(tuple("Hash Maps in Practice", partial.getType(), 0.5, false), tuple("Sorting Fundamentals", standalone.getType(), 1.0, true));
            assertThat(entry.lectureUnits())
                    .extracting(CompetencyIndexDTO.LectureUnitRefDTO::name, CompetencyIndexDTO.LectureUnitRefDTO::type, CompetencyIndexDTO.LectureUnitRefDTO::generatedByAi)
                    .containsExactly(tuple("Hash maps lecture", generatedLecture.getType(), true), tuple("Sorting lecture", instructorLecture.getType(), false));
        });
        assertThat(index.unassignedExercises()).isEmpty();
    }

    @Test
    void listCompetencyIndex_includesUnassignedExercises() {
        Course course = courseWithId(COURSE_ID);
        CourseCompetency competency = newCompetency(5L, "Algorithms and Complexity", "Desc", CompetencyTaxonomy.APPLY, course);
        ProgrammingExercise linked = exerciseInCourse(20L, "Hash Maps in Practice", course);
        ProgrammingExercise unlinked = exerciseInCourse(21L, "Dynamic Programming", course);
        Set<CompetencyExerciseLink> links = new LinkedHashSet<>();
        links.add(new CompetencyExerciseLink(competency, linked, 0.5));
        competency.setExerciseLinks(links);
        when(courseCompetencyRepository.findAllForCourseWithExercisesAndLectureUnitsAndLecturesAndAttachments(COURSE_ID)).thenReturn(Set.of(competency));
        when(exerciseRepository.findAllExercisesByCourseId(COURSE_ID)).thenReturn(Set.of(linked, unlinked));

        CompetencyIndexResponseDTO index = service.listCompetencyIndex(COURSE_ID);

        assertThat(index.unassignedExercises()).singleElement().satisfies(ref -> {
            assertThat(ref.id()).isEqualTo(21L);
            assertThat(ref.title()).isEqualTo("Dynamic Programming");
            assertThat(ref.type()).isEqualTo(unlinked.getType());
        });
    }

    @Test
    void listCompetencyIndex_includesStableCourseRelationReferences() {
        Course course = courseWithId(COURSE_ID);
        CourseCompetency prerequisite = newCompetency(5L, "Foundations", "Desc", CompetencyTaxonomy.UNDERSTAND, course);
        CourseCompetency advanced = newCompetency(6L, "Advanced Algorithms", "Desc", CompetencyTaxonomy.APPLY, course);
        CompetencyRelation relation = new CompetencyRelation();
        relation.setTailCompetency(advanced);
        relation.setHeadCompetency(prerequisite);
        relation.setType(RelationType.ASSUMES);
        when(courseCompetencyRepository.findAllForCourseWithExercisesAndLectureUnitsAndLecturesAndAttachments(COURSE_ID)).thenReturn(Set.of(advanced, prerequisite));
        when(competencyRelationRepository.findAllWithHeadAndTailByCourseId(COURSE_ID)).thenReturn(Set.of(relation));
        when(exerciseRepository.findAllExercisesByCourseId(COURSE_ID)).thenReturn(Set.of());

        CompetencyIndexResponseDTO index = service.listCompetencyIndex(COURSE_ID);

        assertThat(index.competencies()).extracting(CompetencyIndexDTO::id).containsExactly(5L, 6L);
        assertThat(index.competencies()).allSatisfy(entry -> assertThat(entry.relations()).singleElement()
                .satisfies(ref -> assertThat(ref).isEqualTo(new CompetencyIndexDTO.RelationRefDTO(6L, 5L, RelationType.ASSUMES))));
    }

    @Test
    void listCompetencyIndex_noCompetencies_listsAllExercisesAsUnassigned() {
        Course course = courseWithId(COURSE_ID);
        ProgrammingExercise first = exerciseInCourse(30L, "Exercise A", course);
        ProgrammingExercise second = exerciseInCourse(31L, "Exercise B", course);
        when(courseCompetencyRepository.findAllForCourseWithExercisesAndLectureUnitsAndLecturesAndAttachments(COURSE_ID)).thenReturn(Set.of());
        when(exerciseRepository.findAllExercisesByCourseId(COURSE_ID)).thenReturn(Set.of(first, second));

        CompetencyIndexResponseDTO index = service.listCompetencyIndex(COURSE_ID);

        assertThat(index.competencies()).isEmpty();
        assertThat(index.unassignedExercises()).extracting(CompetencyIndexResponseDTO.UnassignedExerciseRefDTO::id).containsExactly(30L, 31L);
    }

    @Test
    void listCompetencyIndex_toolContext_readsCourseIdFromContext() {
        Course course = courseWithId(COURSE_ID);
        CourseCompetency competency = newCompetency(5L, "Algorithms and Complexity", "Desc", CompetencyTaxonomy.APPLY, course);
        when(courseCompetencyRepository.findAllForCourseWithExercisesAndLectureUnitsAndLecturesAndAttachments(COURSE_ID)).thenReturn(Set.of(competency));
        when(exerciseRepository.findAllExercisesByCourseId(COURSE_ID)).thenReturn(Set.of());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(OrchestratorToolContextKeys.COURSE_ID_KEY, COURSE_ID);

        String result = service.listCompetencyIndex(new ToolContext(ctx));

        assertThat(result).contains("Algorithms and Complexity").doesNotContain("No course context");
    }

    @Test
    void listCompetencyIndex_toolContext_missingCourseId_returnsError() {
        String result = service.listCompetencyIndex(new ToolContext(Map.of()));

        assertThat(result).contains("No course context available for this tool call.");
    }

    private static Course courseWithId(long id) {
        Course course = new Course();
        course.setId(id);
        return course;
    }

    private static CourseCompetency newCompetency(long id, String title, String description, CompetencyTaxonomy taxonomy, Course course) {
        Competency competency = new Competency(title, description, null, CourseCompetency.DEFAULT_MASTERY_THRESHOLD, taxonomy, false);
        competency.setId(id);
        competency.setCourse(course);
        return competency;
    }

    private static ProgrammingExercise exerciseInCourse(long id, String title, Course course) {
        ProgrammingExercise exercise = new ProgrammingExercise();
        exercise.setId(id);
        exercise.setTitle(title);
        exercise.setCourse(course);
        return exercise;
    }

    private static TextUnit lectureUnitInCourse(long id, String name, Course course) {
        Lecture lecture = new Lecture();
        lecture.setCourse(course);
        TextUnit unit = new TextUnit();
        unit.setId(id);
        unit.setName(name);
        unit.setLecture(lecture);
        return unit;
    }
}

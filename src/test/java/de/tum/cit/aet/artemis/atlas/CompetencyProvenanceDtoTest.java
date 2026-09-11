package de.tum.cit.aet.artemis.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.atlas.domain.competency.Competency;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyExerciseLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyLectureUnitLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyTaxonomy;
import de.tum.cit.aet.artemis.atlas.domain.competency.CourseCompetency;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyExerciseLinkResponseDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyLectureUnitLinkResponseDTO;
import de.tum.cit.aet.artemis.atlas.dto.CourseCompetencyResponseDTO;
import de.tum.cit.aet.artemis.lecture.domain.Lecture;
import de.tum.cit.aet.artemis.lecture.domain.TextUnit;

/** Verifies the {@code generatedByAi} provenance flag reaches the client through the response DTOs. */
class CompetencyProvenanceDtoTest {

    private static Competency competency(boolean generatedByAi) {
        Competency competency = new Competency("Sorting", "Understand sorting.", null, CourseCompetency.DEFAULT_MASTERY_THRESHOLD, CompetencyTaxonomy.UNDERSTAND, false);
        competency.setId(1L);
        competency.setGeneratedByAi(generatedByAi);
        return competency;
    }

    @Test
    void exerciseLinkResponseCarriesProvenance() {
        CompetencyExerciseLink link = new CompetencyExerciseLink(competency(false), null, 1.0);
        link.setGeneratedByAi(true);

        assertThat(CompetencyExerciseLinkResponseDTO.of(link)).isNotNull().satisfies(dto -> assertThat(dto.generatedByAi()).isTrue());
    }

    @Test
    void lectureUnitLinkResponseCarriesProvenance() {
        TextUnit textUnit = new TextUnit();
        textUnit.setId(3L);
        textUnit.setName("Sorting basics");
        // LectureUnitForCompetencyDTO#of dereferences the lecture, so the unit needs one to map at all.
        textUnit.setLecture(new Lecture());

        CompetencyLectureUnitLink link = new CompetencyLectureUnitLink(competency(false), textUnit, 1.0);
        link.setGeneratedByAi(true);

        assertThat(CompetencyLectureUnitLinkResponseDTO.of(link)).isNotNull().satisfies(dto -> assertThat(dto.generatedByAi()).isTrue());
    }

    @Test
    void courseCompetencyResponseCarriesProvenance() {
        assertThat(CourseCompetencyResponseDTO.of(competency(true)).generatedByAi()).isTrue();
        assertThat(CourseCompetencyResponseDTO.of(competency(false)).generatedByAi()).isFalse();
    }
}

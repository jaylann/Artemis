package de.tum.cit.aet.artemis.atlas.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.atlas.domain.competency.Competency;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyExerciseLink;
import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyTaxonomy;
import de.tum.cit.aet.artemis.atlas.domain.competency.CourseCompetency;
import de.tum.cit.aet.artemis.atlas.domain.competency.Prerequisite;

/**
 * Guards the {@code generatedByAi} provenance flag against the copy-on-create path.
 * <p>
 * {@code CourseCompetencyService#createCourseCompetencies} persists the result of the
 * {@code CourseCompetency} copy constructor rather than the instance it was handed, so a copy
 * constructor that forgets the flag silently discards the orchestrator's authorship on every
 * competency it creates. The service-level tests mock that service away and therefore cannot catch
 * it; these assertions can.
 */
class CompetencyProvenanceTest {

    private static Competency competencyWithProvenance(boolean generatedByAi) {
        Competency competency = new Competency("Sorting", "Understand sorting.", null, CourseCompetency.DEFAULT_MASTERY_THRESHOLD, CompetencyTaxonomy.UNDERSTAND, false);
        competency.setGeneratedByAi(generatedByAi);
        return competency;
    }

    @Test
    void competencyIsInstructorAuthoredByDefault() {
        assertThat(competencyWithProvenance(false).isGeneratedByAi()).isFalse();
    }

    @Test
    void competencyCopyConstructorCarriesProvenance() {
        assertThat(new Competency(competencyWithProvenance(true)).isGeneratedByAi()).isTrue();
        assertThat(new Competency(competencyWithProvenance(false)).isGeneratedByAi()).isFalse();
    }

    @Test
    void prerequisiteCopyConstructorCarriesProvenance() {
        assertThat(new Prerequisite(competencyWithProvenance(true)).isGeneratedByAi()).isTrue();
        assertThat(new Prerequisite(competencyWithProvenance(false)).isGeneratedByAi()).isFalse();
    }

    @Test
    void linkIsInstructorAuthoredByDefault() {
        assertThat(new CompetencyExerciseLink(competencyWithProvenance(true), null, 1.0).isGeneratedByAi()).isFalse();
    }
}

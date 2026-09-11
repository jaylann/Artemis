package de.tum.cit.aet.artemis.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.atlas.dto.AutoOrchestrationSummaryDTO;
import de.tum.cit.aet.artemis.atlas.dto.CompetencyOrchestrationResultDTO;
import de.tum.cit.aet.artemis.atlas.dto.LearningObjectOutcomeDTO;

class OrchestrationOutcomeDtoTest {

    @Test
    void learningObjectOutcome_rejectsInvalidRetryAndUnboundedDetail() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LearningObjectOutcomeDTO(LearningObjectOutcomeDTO.ObjectType.EXERCISE, 1L, LearningObjectOutcomeDTO.Status.PROCESSED, true, "done"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LearningObjectOutcomeDTO(LearningObjectOutcomeDTO.ObjectType.EXERCISE, 1L, LearningObjectOutcomeDTO.Status.FAILED, true, " "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LearningObjectOutcomeDTO(LearningObjectOutcomeDTO.ObjectType.EXERCISE, 1L, LearningObjectOutcomeDTO.Status.FAILED, true, "x".repeat(501)));
    }

    @Test
    void autoSummary_requiresUniqueOutcomesAndDerivedCounts() {
        LearningObjectOutcomeDTO processed = outcome(10L, LearningObjectOutcomeDTO.Status.PROCESSED, false);
        LearningObjectOutcomeDTO failed = outcome(11L, LearningObjectOutcomeDTO.Status.FAILED, true);

        assertThatIllegalArgumentException().isThrownBy(
                () -> new AutoOrchestrationSummaryDTO(1L, "run", CompetencyOrchestrationResultDTO.Status.PARTIAL, 2, 2, 0, 0, List.of(processed, failed), Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AutoOrchestrationSummaryDTO(1L, "run", CompetencyOrchestrationResultDTO.Status.PARTIAL, 2, 1, 1, 0, List.of(processed, processed), Instant.EPOCH));
    }

    @Test
    void resultAndSummary_copyOutcomeLists() {
        List<LearningObjectOutcomeDTO> mutable = new ArrayList<>();
        mutable.add(outcome(10L, LearningObjectOutcomeDTO.Status.PROCESSED, false));
        CompetencyOrchestrationResultDTO result = CompetencyOrchestrationResultDTO.noOp("verified").withObjectOutcomes(mutable);
        AutoOrchestrationSummaryDTO summary = new AutoOrchestrationSummaryDTO(1L, "run", CompetencyOrchestrationResultDTO.Status.NO_OP, 1, 1, 0, 0, mutable, Instant.EPOCH);

        mutable.clear();

        assertThat(result.objectOutcomes()).hasSize(1);
        assertThat(summary.objectOutcomes()).hasSize(1);
    }

    @Test
    void result_rejectsDuplicateObjectOutcomes() {
        LearningObjectOutcomeDTO processed = outcome(10L, LearningObjectOutcomeDTO.Status.PROCESSED, false);

        assertThatIllegalArgumentException().isThrownBy(() -> CompetencyOrchestrationResultDTO.noOp("verified").withObjectOutcomes(List.of(processed, processed)));
    }

    private static LearningObjectOutcomeDTO outcome(long objectId, LearningObjectOutcomeDTO.Status status, boolean retryEligible) {
        return new LearningObjectOutcomeDTO(LearningObjectOutcomeDTO.ObjectType.EXERCISE, objectId, status, retryEligible, "detail");
    }
}

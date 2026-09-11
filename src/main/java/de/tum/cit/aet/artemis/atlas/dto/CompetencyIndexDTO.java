package de.tum.cit.aet.artemis.atlas.dto;

import java.util.List;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.atlas.domain.competency.CompetencyTaxonomy;
import de.tum.cit.aet.artemis.atlas.domain.competency.RelationType;

/**
 * Compact competency entry for the orchestrator's {@code listCompetencyIndex} tool.
 * Exposes identifiers plus linked exercises (with type, current link weight, and provenance) and
 * lecture units (with type and provenance) so the LLM can reason about coverage and evidence
 * strength without issuing additional tool calls for every competency.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompetencyIndexDTO(Long id, String title, CompetencyTaxonomy taxonomy, String type, List<ExerciseLinkRefDTO> exercises, List<LectureUnitRefDTO> lectureUnits,
        List<RelationRefDTO> relations) {

    public CompetencyIndexDTO {
        exercises = exercises == null ? List.of() : List.copyOf(exercises);
        lectureUnits = lectureUnits == null ? List.of() : List.copyOf(lectureUnits);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ExerciseLinkRefDTO(String title, String type, Double weight, boolean generatedByAi) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record LectureUnitRefDTO(String name, String type, boolean generatedByAi) {
    }

    /** Compact directed relation reference: {@code tailCompetencyId --relationType--> headCompetencyId}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record RelationRefDTO(long tailCompetencyId, long headCompetencyId, @NonNull RelationType relationType) {

        public RelationRefDTO {
            if (tailCompetencyId <= 0 || headCompetencyId <= 0) {
                throw new IllegalArgumentException("relation competency ids must be positive");
            }
        }
    }
}

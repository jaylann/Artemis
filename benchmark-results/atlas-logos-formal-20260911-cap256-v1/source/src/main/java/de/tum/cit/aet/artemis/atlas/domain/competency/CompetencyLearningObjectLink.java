package de.tum.cit.aet.artemis.atlas.domain.competency;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.MapsId;

@MappedSuperclass
public abstract class CompetencyLearningObjectLink implements Serializable {

    @ManyToOne(optional = false)
    @MapsId("competencyId")
    protected CourseCompetency competency;

    @Column(name = "link_weight")
    protected double weight;

    /**
     * Whether this link was persisted by the competency orchestrator rather than by an instructor.
     * <p>
     * The orchestrator writes links automatically, without the per-change approval the chat-based
     * Atlas Agent requires, so provenance would otherwise be lost. The flag lets the UI attribute a
     * link to an agent and lets the orchestrator leave instructor-authored links alone. It is
     * written server-side only — never accepted from a client payload — so a caller cannot
     * mislabel their own link as agent-authored.
     */
    @Column(name = "generated_by_ai", nullable = false)
    protected boolean generatedByAi = false;

    public CompetencyLearningObjectLink(CourseCompetency competency, double weight) {
        this.competency = competency;
        this.weight = weight;
    }

    public CompetencyLearningObjectLink() {
        // Empty constructor for Spring
    }

    public CourseCompetency getCompetency() {
        return competency;
    }

    public void setCompetency(CourseCompetency competency) {
        this.competency = competency;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public boolean isGeneratedByAi() {
        return generatedByAi;
    }

    public void setGeneratedByAi(boolean generatedByAi) {
        this.generatedByAi = generatedByAi;
    }
}

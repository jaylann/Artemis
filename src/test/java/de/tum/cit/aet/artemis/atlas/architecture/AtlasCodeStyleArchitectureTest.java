package de.tum.cit.aet.artemis.atlas.architecture;

import de.tum.cit.aet.artemis.shared.architecture.module.AbstractModuleCodeStyleTest;

class AtlasCodeStyleArchitectureTest extends AbstractModuleCodeStyleTest {

    @Override
    public String getModulePackage() {
        return ARTEMIS_PACKAGE + ".atlas";
    }

    @Override
    protected int dtoNameEndingThreshold() {
        // Includes LearningObjectOutcomeDTO.ObjectType and Status, which are enums rather than transport records.
        return 8;
    }
}

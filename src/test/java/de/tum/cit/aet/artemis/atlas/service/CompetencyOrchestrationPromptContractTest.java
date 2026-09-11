package de.tum.cit.aet.artemis.atlas.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/** Verifies requirement-level protocol elements that must remain explicit in the Atlas orchestrator prompt. */
class CompetencyOrchestrationPromptContractTest {

    @Test
    void executePrompt_requiresCompleteInternalPlanItems() throws IOException {
        try (InputStream promptStream = getClass().getResourceAsStream("/prompts/atlas/orchestrator_execute_prompt.st")) {
            assertThat(promptStream).isNotNull();
            String prompt = new String(promptStream.readAllBytes(), UTF_8);

            assertThat(prompt).contains("operation type", "target", "rationale", "expected postcondition");
        }
    }
}

package de.tum.cit.aet.artemis.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Guards constructor binding for Atlas orchestrator defaults and worker-specific overrides.
 */
class AtlasOrchestratorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsDefaultsIncludingDisabledResponsesApi() {
        contextRunner.run(context -> {
            AtlasOrchestratorProperties properties = context.getBean(AtlasOrchestratorProperties.class);

            assertThat(properties.model()).isEqualTo("gpt-5.6-luna");
            assertThat(properties.reasoningEffort()).isEqualTo("xhigh");
            assertThat(properties.workerModel()).isEqualTo("gpt-5.6-luna");
            assertThat(properties.workerReasoningEffort()).isEqualTo("high");
            assertThat(properties.responsesApiEnabled()).isFalse();
        });
    }

    @Test
    void bindsResponsesApiFlagAndWorkerValues() {
        contextRunner.withPropertyValues("artemis.atlas.orchestrator.responses-api-enabled=true", "artemis.atlas.orchestrator.worker-model=worker-model",
                "artemis.atlas.orchestrator.worker-reasoning-effort=medium").run(context -> {
                    AtlasOrchestratorProperties properties = context.getBean(AtlasOrchestratorProperties.class);

                    assertThat(properties.responsesApiEnabled()).isTrue();
                    assertThat(properties.workerModel()).isEqualTo("worker-model");
                    assertThat(properties.workerReasoningEffort()).isEqualTo("medium");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AtlasOrchestratorProperties.class)
    static class PropertiesConfiguration {
    }
}

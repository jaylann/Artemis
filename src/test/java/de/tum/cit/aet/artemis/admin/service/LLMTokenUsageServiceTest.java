package de.tum.cit.aet.artemis.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.artemis.admin.domain.LLMRequest;
import de.tum.cit.aet.artemis.core.config.LLMModelCostConfiguration;
import de.tum.cit.aet.artemis.core.test_repository.LLMTokenUsageRequestTestRepository;
import de.tum.cit.aet.artemis.core.test_repository.LLMTokenUsageTraceTestRepository;

class LLMTokenUsageServiceTest {

    @Mock
    private LLMTokenUsageTraceTestRepository llmTokenUsageTraceRepository;

    @Mock
    private LLMTokenUsageRequestTestRepository llmTokenUsageRequestRepository;

    private LLMTokenUsageService llmTokenUsageService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        llmTokenUsageService = new LLMTokenUsageService(llmTokenUsageTraceRepository, llmTokenUsageRequestRepository, createCostConfiguration());
    }

    @Test
    void buildLLMRequest_withDashedDateSuffix_usesConfiguredCost() {
        LLMRequest request = llmTokenUsageService.buildLLMRequest("gpt-5-mini-2025-08-07", 11, 7, "PIPE");

        assertThat(request.model()).isEqualTo("gpt-5-mini-2025-08-07");
        assertThat(request.numInputTokens()).isEqualTo(11);
        assertThat(request.numOutputTokens()).isEqualTo(7);
        assertThat(request.costPerMillionInputToken()).isEqualTo(0.23f);
        assertThat(request.costPerMillionOutputToken()).isEqualTo(1.84f);
        assertThat(request.pipelineId()).isEqualTo("PIPE");
    }

    @Test
    void buildLLMRequest_withDateSuffixWithoutSeparator_usesConfiguredCost() {
        LLMRequest request = llmTokenUsageService.buildLLMRequest("gpt-5-mini2025-08-07", 11, 7, "PIPE");

        assertThat(request.costPerMillionInputToken()).isEqualTo(0.23f);
        assertThat(request.costPerMillionOutputToken()).isEqualTo(1.84f);
    }

    @Test
    void buildLLMRequest_withDashlessVariant_usesStrippedFallback() {
        LLMRequest request = llmTokenUsageService.buildLLMRequest("gpt5mini-2025-08-07", 11, 7, "PIPE");

        assertThat(request.costPerMillionInputToken()).isEqualTo(0.23f);
        assertThat(request.costPerMillionOutputToken()).isEqualTo(1.84f);
    }

    @Test
    void buildLLMRequest_withDottedModel_andEnvStyleStrippedKey_usesStrippedFallback() {
        // Env-var configuration strips dots and dashes, so "gpt-5.4" is configured as the key "gpt54".
        // The runtime model name "gpt-5.4" must still resolve to that cost via the stripped fallback.
        LLMModelCostConfiguration configuration = new LLMModelCostConfiguration();
        LLMModelCostConfiguration.ModelCostProperties dottedModel = new LLMModelCostConfiguration.ModelCostProperties();
        dottedModel.setInputCostPerMillionEur(2.30f);
        dottedModel.setOutputCostPerMillionEur(13.80f);
        configuration.setModelCosts(Map.of("gpt54", dottedModel));
        LLMTokenUsageService service = new LLMTokenUsageService(llmTokenUsageTraceRepository, llmTokenUsageRequestRepository, configuration);

        LLMRequest request = service.buildLLMRequest("gpt-5.4", 11, 7, "PIPE");

        assertThat(request.costPerMillionInputToken()).isEqualTo(2.30f);
        assertThat(request.costPerMillionOutputToken()).isEqualTo(13.80f);
    }

    @Test
    void buildLLMRequest_withUnknownModel_returnsZeroCosts() {
        LLMRequest request = llmTokenUsageService.buildLLMRequest("unknown-model-2025-08-07", 11, 7, "PIPE");

        assertThat(request.costPerMillionInputToken()).isEqualTo(0.0f);
        assertThat(request.costPerMillionOutputToken()).isEqualTo(0.0f);
    }

    @Test
    void constructor_withStrippedModelCostCollision_throwsIllegalStateException() {
        LLMModelCostConfiguration configuration = new LLMModelCostConfiguration();
        LLMModelCostConfiguration.ModelCostProperties dashedModel = new LLMModelCostConfiguration.ModelCostProperties();
        dashedModel.setInputCostPerMillionEur(0.23f);
        dashedModel.setOutputCostPerMillionEur(1.84f);
        LLMModelCostConfiguration.ModelCostProperties dashlessModel = new LLMModelCostConfiguration.ModelCostProperties();
        dashlessModel.setInputCostPerMillionEur(0.10f);
        dashlessModel.setOutputCostPerMillionEur(0.20f);
        configuration.setModelCosts(Map.of("gpt-5-mini", dashedModel, "gpt5-mini", dashlessModel));

        assertThatThrownBy(() -> new LLMTokenUsageService(llmTokenUsageTraceRepository, llmTokenUsageRequestRepository, configuration)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-5-mini").hasMessageContaining("gpt5-mini").hasMessageContaining("gpt5mini");
    }

    @Test
    void trackChatResponseTokenUsage_withMissingResponseData_logsFailuresWithoutPersistence() {
        Logger logger = (Logger) LoggerFactory.getLogger(LLMTokenUsageService.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        try {
            llmTokenUsageService.trackChatResponseTokenUsage(null, null, "NULL_RESPONSE", builder -> builder);

            ChatResponse responseWithoutMetadata = mock(ChatResponse.class);
            llmTokenUsageService.trackChatResponseTokenUsage(responseWithoutMetadata, null, "NO_METADATA", builder -> builder);

            ChatResponseMetadata metadataWithoutUsage = mock(ChatResponseMetadata.class);
            ChatResponse responseWithoutUsage = mock(ChatResponse.class);
            when(responseWithoutUsage.getMetadata()).thenReturn(metadataWithoutUsage);
            llmTokenUsageService.trackChatResponseTokenUsage(responseWithoutUsage, null, "NO_USAGE", builder -> builder);

            assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN).extracting(ILoggingEvent::getFormattedMessage).contains(
                    "Failed to store token usage for pipeline [NULL_RESPONSE]: chat response is missing.",
                    "Failed to store token usage for pipeline [NO_METADATA]: response metadata is missing.",
                    "Failed to store token usage for pipeline [NO_USAGE]: usage metadata is missing.");
            verifyNoInteractions(llmTokenUsageTraceRepository, llmTokenUsageRequestRepository);
        }
        finally {
            logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    private static LLMModelCostConfiguration createCostConfiguration() {
        LLMModelCostConfiguration costConfiguration = new LLMModelCostConfiguration();
        LLMModelCostConfiguration.ModelCostProperties modelCostProperties = new LLMModelCostConfiguration.ModelCostProperties();
        modelCostProperties.setInputCostPerMillionEur(0.23f);
        modelCostProperties.setOutputCostPerMillionEur(1.84f);
        costConfiguration.setModelCosts(Map.of("gpt-5-mini", modelCostProperties));
        return costConfiguration;
    }
}

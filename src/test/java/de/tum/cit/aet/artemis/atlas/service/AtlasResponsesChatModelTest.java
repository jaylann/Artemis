package de.tum.cit.aet.artemis.atlas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.services.blocking.ResponseService;

/**
 * Verifies the narrow Responses adapter and its native Spring AI tool-calling integration.
 */
@ExtendWith(MockitoExtension.class)
class AtlasResponsesChatModelTest {

    @Mock
    private OpenAIClient openAIClient;

    @Mock
    private ResponseService responseService;

    @BeforeEach
    void setUp() {
        when(openAIClient.responses()).thenReturn(responseService);
    }

    @Test
    void nativeToolCallingAdvisor_executesCallbackAndReplaysOrderedResponsesItems() {
        ResponseFunctionToolCall functionCall = functionCall();
        ResponseReasoningItem firstReasoning = reasoning("sealed-first");
        ResponseReasoningItem secondReasoning = reasoning("sealed-second");
        Response firstResponse = response(List.of(ResponseOutputItem.ofReasoning(firstReasoning), ResponseOutputItem.ofFunctionCall(functionCall)), "first");
        Response secondResponse = response(List.of(ResponseOutputItem.ofReasoning(secondReasoning), ResponseOutputItem.ofMessage(message("done"))), "second");
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(firstResponse, secondResponse);

        AtomicReference<String> contextMarker = new AtomicReference<>();
        ToolCallback callback = FunctionToolCallback
                .<Map<String, Object>, String>builder("lookup", (arguments, context) -> toolResult(arguments.get("value").toString(), context, contextMarker))
                .description("Looks up a value.").inputType(Map.class).inputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}").build();
        ChatClient chatClient = ChatClient.builder(new AtlasResponsesChatModel(openAIClient, new ObjectMapper(), "gpt-test"))
                .defaultAdvisors(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder().build()).build();

        ChatResponse result = chatClient.prompt().user("find it").options(OpenAiChatOptions.builder().deploymentName("gpt-test")).toolContext(Map.of("marker", "ctx"))
                .toolCallbacks(callback).call().chatResponse();

        assertThat(result.getResult().getOutput().getText()).isEqualTo("done");
        assertThat(contextMarker).hasValue("ctx");

        ArgumentCaptor<ResponseCreateParams> requestCaptor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        org.mockito.Mockito.verify(responseService, org.mockito.Mockito.times(2)).create(requestCaptor.capture());
        List<ResponseInputItem> secondInput = requestCaptor.getAllValues().get(1).input().orElseThrow().asResponse();
        assertThat(secondInput).hasSize(4);
        assertThat(secondInput.get(0).easyInputMessage()).isPresent();
        assertThat(secondInput.get(1).reasoning()).hasValue(firstReasoning);
        assertThat(secondInput.get(2).functionCall()).hasValue(functionCall);
        assertThat(secondInput.get(3).functionCallOutput()).get().extracting(ResponseInputItem.FunctionCallOutput::callId).isEqualTo("call-1");
        assertThat(requestCaptor.getAllValues().get(0).store()).hasValue(false);
        assertThat(requestCaptor.getAllValues().get(0).include()).hasValue(List.of(com.openai.models.responses.ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
    }

    @Test
    void missingUsageIsPreservedAsAbsent() {
        Response responseWithoutUsage = response(List.of(ResponseOutputItem.ofMessage(message("done"))), "missing-usage");
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithoutUsage);

        ChatResponse result = new AtlasResponsesChatModel(openAIClient, new ObjectMapper(), "gpt-test")
                .call(new Prompt(List.of(new UserMessage("hello")), OpenAiChatOptions.builder().deploymentName("gpt-test").build()));

        // Spring AI supplies EmptyUsage when provider usage is absent; the HTTP ledger retains unknown cost.
        assertThat(result.getMetadata().getUsage()).isInstanceOf(org.springframework.ai.chat.metadata.EmptyUsage.class);
    }

    @ParameterizedTest
    @MethodSource("responseModels")
    void preservesResponseModelIdentity(ResponsesModel responseModel, String expectedModel) {
        Response responseWithModel = response(List.of(ResponseOutputItem.ofMessage(message("done"))), "model-identity");
        when(responseWithModel.model()).thenReturn(responseModel);
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithModel);

        ChatResponse result = new AtlasResponsesChatModel(openAIClient, new ObjectMapper(), "gpt-test")
                .call(new Prompt(new UserMessage("hello"), OpenAiChatOptions.builder().deploymentName("gpt-test").build()));

        assertThat(result.getMetadata().getModel()).isEqualTo(expectedModel);
    }

    private static Stream<Arguments> responseModels() {
        return Stream.of(Arguments.of(ResponsesModel.ofString("custom-string"), "custom-string"),
                Arguments.of(ResponsesModel.ofChat(ChatModel.GPT_5_6_LUNA), ChatModel.GPT_5_6_LUNA.asString()),
                Arguments.of(ResponsesModel.ofOnly(ResponsesModel.ResponsesOnlyModel.O3_PRO), ResponsesModel.ResponsesOnlyModel.O3_PRO.asString()));
    }

    @Test
    void failedAndIncompleteResponsesAreRejected() {
        Response incomplete = response(List.of(ResponseOutputItem.ofMessage(message("partial"))), "incomplete");
        when(incomplete.status()).thenReturn(Optional.of(ResponseStatus.INCOMPLETE));
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(incomplete);

        assertThatThrownBy(() -> new AtlasResponsesChatModel(openAIClient, new ObjectMapper(), "gpt-test")
                .call(new Prompt(new UserMessage("hello"), OpenAiChatOptions.builder().deploymentName("gpt-test").build()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    private static String toolResult(String arguments, ToolContext context, AtomicReference<String> marker) {
        marker.set((String) context.getContext().get("marker"));
        return "result:" + arguments;
    }

    private static Response response(List<ResponseOutputItem> output, String id) {
        Response response = mock(Response.class);
        lenient().when(response.id()).thenReturn(id);
        lenient().when(response.model()).thenReturn(ResponsesModel.ofString("gpt-test"));
        lenient().when(response.output()).thenReturn(output);
        lenient().when(response.status()).thenReturn(Optional.of(ResponseStatus.COMPLETED));
        lenient().when(response.error()).thenReturn(Optional.empty());
        lenient().when(response.incompleteDetails()).thenReturn(Optional.empty());
        lenient().when(response.usage()).thenReturn(Optional.empty());
        return response;
    }

    private static ResponseFunctionToolCall functionCall() {
        return ResponseFunctionToolCall.builder().id("fc-1").type(JsonValue.from("function_call")).callId("call-1").name("lookup").arguments("{\"value\":\"x\"}")
                .status(ResponseFunctionToolCall.Status.COMPLETED).build();
    }

    private static ResponseReasoningItem reasoning(String encryptedContent) {
        return ResponseReasoningItem.builder().id("rs-1").type(JsonValue.from("reasoning")).summary(List.of()).encryptedContent(encryptedContent)
                .status(ResponseReasoningItem.Status.COMPLETED).build();
    }

    private static ResponseOutputMessage message(String text) {
        ResponseOutputText outputText = ResponseOutputText.builder().type(JsonValue.from("output_text")).text(text).annotations(List.of()).build();
        return ResponseOutputMessage.builder().id("msg-1").type(JsonValue.from("message")).role(JsonValue.from("assistant")).status(ResponseOutputMessage.Status.COMPLETED)
                .content(List.of(ResponseOutputMessage.Content.ofOutputText(outputText))).build();
    }
}

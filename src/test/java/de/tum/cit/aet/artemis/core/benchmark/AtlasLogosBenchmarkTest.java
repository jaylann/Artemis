package de.tum.cit.aet.artemis.core.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class AtlasLogosBenchmarkTest {

    private static final String MODEL = "gpt-5.6-luna";

    private static final URI ENDPOINT = URI.create("http://127.0.0.1:18090/v1/chat/completions");

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

    @TempDir
    Path evidence;

    @Test
    void settlesDistinctCachedAndReasoningTokensAndWritesImmutableRecords() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run");
            AtlasLogosBenchmark.Reservation reservation = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-hash");
            telemetry.complete(reservation, request(), response(200, false), new AtlasLogosBenchmark.Usage(20L, 5L, 7L, 2L, "upstream", MODEL, 1), NOW);
            scope.complete("completed", false);
            scope.close();
        }
        JsonNode provider = mapper.readTree(Files.readAllLines(evidence.resolve("provider-events.jsonl")).getFirst());
        JsonNode invocation = mapper.readTree(Files.readAllLines(evidence.resolve("invocation-events.jsonl")).getFirst());
        assertThat(provider.path("inputTokens").asLong()).isEqualTo(20);
        assertThat(provider.path("cachedInputTokens").asLong()).isEqualTo(5);
        assertThat(provider.path("outputTokens").asLong()).isEqualTo(7);
        assertThat(provider.path("reasoningTokens").asLong()).isEqualTo(2);
        assertThat(provider.path("logosRequestId").asText()).isEqualTo("logos-1");
        assertThat(provider.path("costEur").asDouble()).isGreaterThan(0);
        assertThat(invocation.path("terminalStatus").asText()).isEqualTo("completed");
        assertThat(invocation.path("usageComplete").asBoolean()).isTrue();
        assertThat(provider.path("sequence").asLong()).isLessThan(invocation.path("sequence").asLong());
    }

    @Test
    void settlesCacheWriteTokensAndUsesTheWriteRateForReservation() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        JsonNode responseJson = mapper
                .readTree("{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":20,\"cache_write_tokens\":30},\"output_tokens\":10}}");
        AtlasLogosBenchmark.Usage parsed = AtlasLogosBenchmark.Usage.parse(responseJson, "response", MODEL);
        assertThat(parsed.available()).isTrue();
        assertThat(parsed.cacheWrite()).isEqualTo(30L);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run")) {
                AtlasLogosBenchmark.Reservation reservation = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-hash");
                telemetry.complete(reservation, request(), response(200, false), new AtlasLogosBenchmark.Usage(100L, 20L, 30L, 10L, 0L, "response", MODEL, 0), NOW);
                scope.complete("completed", false);
            }
        }
        JsonNode provider = mapper.readTree(Files.readAllLines(evidence.resolve("provider-events.jsonl")).getFirst());
        assertThat(provider.path("cacheWriteInputTokens").asLong()).isEqualTo(30L);
        assertThat(provider.path("costEur").asDouble()).isCloseTo(0.000025515, org.assertj.core.data.Offset.offset(0.000000000001));
        JsonNode reservation = mapper.readTree(Files.readAllLines(evidence.resolve("dispatch-journal.jsonl")).getFirst()).path("reservation");
        assertThat(reservation.path("inputBound").asLong()).isEqualTo(1256L);
        assertThat(reservation.path("reservedCostEur").asDouble()).isGreaterThan(0.1321069);
    }

    @Test
    void missingLunaCacheWriteCountIsUnknown() throws Exception {
        ObjectMapper mapper = mapper();
        var usage = mapper.createObjectNode();
        usage.put("input_tokens", 10).put("output_tokens", 1);
        usage.putObject("input_tokens_details").put("cached_tokens", 0);
        var response = mapper.createObjectNode().set("usage", usage);
        assertThat(AtlasLogosBenchmark.Usage.parse(response, "id", MODEL).available()).isFalse();
        assertThat(AtlasLogosBenchmark.Usage.parse(response, "id", "gpt-5.4-mini").available()).isTrue();
    }

    @Test
    void rejectsInvalidOrUnsupportedCacheWriteUsage() throws Exception {
        ObjectMapper mapper = mapper();
        assertThat(AtlasLogosBenchmark.Usage
                .parse(mapper.readTree("{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":80,\"cache_write_tokens\":30},\"output_tokens\":1}}"), "id",
                        MODEL)
                .available()).isFalse();
        assertThat(AtlasLogosBenchmark.Usage
                .parse(mapper.readTree("{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":null},\"output_tokens\":1}}"), "id",
                        MODEL)
                .available()).isFalse();
        assertThat(AtlasLogosBenchmark.Usage
                .parse(mapper.readTree("{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":0,\"cache_creation_input_tokens\":1},\"output_tokens\":1}}"),
                        "id", MODEL)
                .available()).isFalse();
    }

    @Test
    void keepsRetryReservationForRetryableMissingUsageAndAllowsNextObservation() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run");
            AtlasLogosBenchmark.Reservation first = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-1");
            telemetry.complete(first, request(), response(429, true), AtlasLogosBenchmark.Usage.unavailable(null, MODEL), NOW, true);
            AtlasLogosBenchmark.Reservation retry = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-2");
            telemetry.complete(retry, request(), response(200, false), new AtlasLogosBenchmark.Usage(1L, 0L, 1L, 0L, "upstream-2", MODEL, 0), NOW);
            scope.complete("completed", false);
            scope.close();
            ObjectNode active = (ObjectNode) mapper.readTree(Files.readString(evidence.resolve("active-run.json")));
            active.put("currentObservationId", "next-observation").put("currentInvocationId", "next-invocation");
            mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
            AtlasLogosBenchmark.Telemetry.Scope next = telemetry.begin(7L, "next");
            AtlasLogosBenchmark.Reservation nextReservation = telemetry.reserve(ENDPOINT, MODEL, 1000, "next-request");
            telemetry.complete(nextReservation, request(), response(200, false), new AtlasLogosBenchmark.Usage(1L, 0L, 1L, 0L, "upstream-3", MODEL, 0), NOW);
            next.complete("completed", false);
            next.close();
        }
        assertThat(Files.readAllLines(evidence.resolve("dispatch-journal.jsonl"))).hasSize(5);
        JsonNode terminal = mapper.readTree(Files.readAllLines(evidence.resolve("invocation-events.jsonl")).getFirst());
        assertThat(terminal.path("usageComplete").asBoolean()).isFalse();
        assertThat(terminal.path("terminalStatus").asText()).isEqualTo("completed");
    }

    @Test
    void allowsThirdSameInvocationReservationAfterSettlingOneConcurrentAttempt() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run")) {
                AtlasLogosBenchmark.Reservation first = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-1");
                AtlasLogosBenchmark.Reservation second = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-2");
                telemetry.complete(second, request(), response(200, false), new AtlasLogosBenchmark.Usage(1L, 0L, 1L, 0L, "upstream-2", MODEL, 0), NOW);
                AtlasLogosBenchmark.Reservation third = telemetry.reserve(ENDPOINT, MODEL, 1000, "request-3");
                assertThat(third).isNotNull();
                telemetry.complete(third, request(), response(200, false), new AtlasLogosBenchmark.Usage(1L, 0L, 1L, 0L, "upstream-3", MODEL, 0), NOW);
                telemetry.complete(first, request(), response(429, true), AtlasLogosBenchmark.Usage.unavailable(null, MODEL), NOW, true);
                scope.complete("completed", false);
            }
        }
    }

    @Test
    void observedIncompleteUsageAllowsRestartButUncapturedReserveBlocksAndExhaustionWritesStop() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run");
            AtlasLogosBenchmark.Reservation reservation = telemetry.reserve(ENDPOINT, MODEL, 1000, "request");
            telemetry.complete(reservation, request(), response(500, true), AtlasLogosBenchmark.Usage.unavailable(null, MODEL), NOW, true);
            scope.complete("failed", true);
            scope.close();
        }
        ObjectNode active = (ObjectNode) mapper.readTree(Files.readString(evidence.resolve("active-run.json")));
        active.put("currentObservationId", "next-observation").put("currentInvocationId", "new-invocation");
        mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
        try (AtlasLogosBenchmark.Ledger restarted = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(restarted, clock());
            try (AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "restart")) {
                AtlasLogosBenchmark.Reservation next = telemetry.reserve(ENDPOINT, MODEL, 1000, "request");
                telemetry.complete(next, request(), response(200, false), new AtlasLogosBenchmark.Usage(1L, 0L, 1L, 0L, "upstream-2", MODEL, 0), NOW);
                scope.complete("completed", false);
            }
        }

        Files.deleteIfExists(evidence.resolve("invocation-events.jsonl"));
        Files.deleteIfExists(evidence.resolve("provider-events.jsonl"));
        Files.deleteIfExists(evidence.resolve("dispatch-journal.jsonl"));
        Files.deleteIfExists(evidence.resolve("STOP"));
        writeEvidence(mapper, 0.4, "gpt-5.4-mini");
        try (AtlasLogosBenchmark.Ledger exhausted = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(exhausted, clock());
            AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "budget");
            assertThatThrownBy(() -> telemetry.reserve(ENDPOINT, "gpt-5.4-mini", 1000, "request")).isInstanceOf(AtlasLogosBenchmark.DispatchBlockedException.class);
            scope.close();
        }
        assertThat(Files.readString(evidence.resolve("STOP"))).isEqualTo("budget exhausted before dispatch" + System.lineSeparator());
    }

    @Test
    void uncapturedReservationBlocksAfterRestart() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run")) {
                telemetry.reserve(ENDPOINT, MODEL, 1000, "uncaptured-request");
                scope.complete("failed", true);
            }
        }
        ObjectNode active = (ObjectNode) mapper.readTree(Files.readString(evidence.resolve("active-run.json")));
        active.put("currentObservationId", "next-observation").put("currentInvocationId", "restart-invocation");
        mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
        try (AtlasLogosBenchmark.Ledger restarted = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(restarted, clock());
            try (AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "restart")) {
                assertThatThrownBy(() -> telemetry.reserve(ENDPOINT, MODEL, 1000, "request")).isInstanceOf(AtlasLogosBenchmark.DispatchBlockedException.class);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "smoke", "formal" })
    void enforcesModeCeilingIncludingOutstandingReservations(String mode) throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 100.0, MODEL);
        ObjectNode active = (ObjectNode) mapper.readTree(evidence.resolve("active-run.json").toFile());
        active.put("mode", mode);
        mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "budget")) {
                AtlasLogosBenchmark.Reservation first = telemetry.reserve(ENDPOINT, MODEL, 1000, "first");
                int allowed = (int) Math.floor(("smoke".equals(mode) ? 1.0 : 20.0) / first.reservedCostEur());
                for (int attempt = 1; attempt < allowed; attempt++) {
                    telemetry.reserve(ENDPOINT, MODEL, 1000, "request");
                }
                assertThatThrownBy(() -> telemetry.reserve(ENDPOINT, MODEL, 1000, "overflow")).isInstanceOf(AtlasLogosBenchmark.DispatchBlockedException.class);
                scope.complete("failed", true);
            }
        }
    }

    @Test
    void nestedPhaseCountsToolsAndParsesCachedUsage() throws Exception {
        ObjectMapper mapper = mapper();
        JsonNode response = mapper.readTree(
                "{\"usage\":{\"input_tokens\":3,\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},\"output_tokens\":2},\"output\":[{\"type\":\"function_call\"}]}");
        AtlasLogosBenchmark.Usage usage = AtlasLogosBenchmark.Usage.parse(response, "id", MODEL);
        assertThat(usage.available()).isTrue();
        assertThat(usage.cachedInput()).isZero();
        assertThat(usage.toolCalls()).isEqualTo(1);
        writeEvidence(mapper, 1.0, MODEL);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "run");
            try (AtlasLogosBenchmark.Telemetry.Phase ignored = telemetry.phase("delegation"); AtlasLogosBenchmark.Telemetry.Phase tool = telemetry.phase("tool")) {
                assertThat(tool).isNotNull();
            }
            scope.close();
        }
    }

    @Test
    void interruptedActiveRunFailsClosedBeforeDispatch() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        ObjectNode active = (ObjectNode) mapper.readTree(Files.readString(evidence.resolve("active-run.json")));
        active.put("status", "interrupted");
        mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            AtlasLogosBenchmark.Telemetry.Scope scope = telemetry.begin(7L, "interrupted");
            assertThatThrownBy(() -> telemetry.reserve(ENDPOINT, MODEL, 1000, "request")).isInstanceOf(AtlasLogosBenchmark.DispatchBlockedException.class);
            scope.close();
        }
    }

    @Test
    void interceptorPreservesWireBytesAndRecordsSdkRetryAndPhase() throws Exception {
        ObjectMapper mapper = mapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        writeEvidence(mapper, 1.0, MODEL);
        String input = "{\"model\":\"gpt-5.6-luna\",\"reasoning_effort\":\"high\",\"messages\":[{\"role\":\"user\",\"content\":\"private fixture text\"}]}";
        String output = "{\"id\":\"response1\",\"model\":\"gpt-5.6-luna\",\"usage\":{\"prompt_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":5,\"cache_write_tokens\":0},\"completion_tokens\":7,\"completion_tokens_details\":{\"reasoning_tokens\":2}}}";
        okhttp3.MediaType json = okhttp3.MediaType.get("application/json");
        okhttp3.Request original = new okhttp3.Request.Builder().url(ENDPOINT.toString()).header("x-stainless-retry-count", "2").post(okhttp3.RequestBody.create(input, json))
                .build();
        okhttp3.Interceptor.Chain chain = org.mockito.Mockito.mock(okhttp3.Interceptor.Chain.class);
        org.mockito.Mockito.when(chain.request()).thenReturn(original);
        org.mockito.Mockito.when(chain.proceed(org.mockito.ArgumentMatchers.any(okhttp3.Request.class))).thenReturn(new okhttp3.Response.Builder().request(original)
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").header("X-Request-ID", "logos-1").body(okhttp3.ResponseBody.create(output, json)).build());
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (var scope = telemetry.begin(7L, "run"); var phase = telemetry.phase("worker")) {
                try (okhttp3.Response response = new AtlasLogosBenchmarkInterceptor(telemetry, mapper, clock()).intercept(chain)) {
                    assertThat(response.body().string()).isEqualTo(output);
                }
                scope.complete("completed", false);
            }
        }
        var captured = org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
        org.mockito.Mockito.verify(chain).proceed(captured.capture());
        okio.Buffer bytes = new okio.Buffer();
        captured.getValue().body().writeTo(bytes);
        assertThat(bytes.readUtf8()).isEqualTo(input);
        var lines = Files.readAllLines(evidence.resolve("provider-events.jsonl"));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).doesNotContain("private fixture text");
        JsonNode event = mapper.readTree(lines.getFirst());
        assertThat(event.path("phase").asText()).isEqualTo("worker");
        assertThat(event.path("sdkRetryCount").asInt()).isEqualTo(2);
        assertThat(event.path("reasoningEffort").asText()).isEqualTo("high");
    }

    @Test
    void exhaustedBudgetNeverCallsTheHttpChain() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 0.01, MODEL);
        okhttp3.Request request = new okhttp3.Request.Builder().url(ENDPOINT.toString())
                .post(okhttp3.RequestBody.create("{\"model\":\"gpt-5.6-luna\",\"messages\":[]}", okhttp3.MediaType.get("application/json"))).build();
        okhttp3.Interceptor.Chain chain = org.mockito.Mockito.mock(okhttp3.Interceptor.Chain.class);
        org.mockito.Mockito.when(chain.request()).thenReturn(request);
        try (AtlasLogosBenchmark.Ledger ledger = open(mapper)) {
            AtlasLogosBenchmark.Telemetry telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (var scope = telemetry.begin(7L, "run")) {
                assertThatThrownBy(() -> new AtlasLogosBenchmarkInterceptor(telemetry, mapper, clock()).intercept(chain))
                        .isInstanceOf(AtlasLogosBenchmark.DispatchBlockedException.class);
            }
        }
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.never()).proceed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void responsesAttemptPreservesBytesAndRecordsReasoningAndCachedUsage() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        String input = "{\"model\":\"gpt-5.6-luna\",\"store\":false,\"reasoning\":{\"effort\":\"xhigh\"},\"input\":[{\"role\":\"user\",\"content\":\"private fixture\"}]}";
        String output = "{\"id\":\"resp-test\",\"model\":\"gpt-5.6-luna\",\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}],\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":40,\"cache_write_tokens\":0},\"output_tokens\":20,\"output_tokens_details\":{\"reasoning_tokens\":15}}}";
        okhttp3.Request request = new okhttp3.Request.Builder().url("http://127.0.0.1:18090/v1/responses")
                .post(okhttp3.RequestBody.create(input, okhttp3.MediaType.get("application/json"))).build();
        okhttp3.Interceptor.Chain chain = org.mockito.Mockito.mock(okhttp3.Interceptor.Chain.class);
        org.mockito.Mockito.when(chain.request()).thenReturn(request);
        org.mockito.Mockito.when(chain.proceed(org.mockito.ArgumentMatchers.any())).thenReturn(new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").header("X-Request-ID", "logos-response").body(okhttp3.ResponseBody.create(output, okhttp3.MediaType.get("application/json"))).build());
        try (var ledger = open(mapper)) {
            var telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (var scope = telemetry.begin(7L, "run"); var response = new AtlasLogosBenchmarkInterceptor(telemetry, mapper, clock()).intercept(chain)) {
                assertThat(response.body().string()).isEqualTo(output);
                scope.complete("completed", false);
            }
        }
        var captured = org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
        org.mockito.Mockito.verify(chain).proceed(captured.capture());
        okio.Buffer bytes = new okio.Buffer();
        captured.getValue().body().writeTo(bytes);
        assertThat(bytes.readUtf8()).isEqualTo(input);
        String evidenceLine = Files.readString(evidence.resolve("provider-events.jsonl"));
        assertThat(evidenceLine).doesNotContain("private fixture");
        JsonNode event = mapper.readTree(evidenceLine);
        assertThat(event.path("reasoningEffort").asText()).isEqualTo("xhigh");
        assertThat(event.path("cachedInputTokens").asLong()).isEqualTo(40);
        assertThat(event.path("outputTokens").asLong()).isEqualTo(20);
        assertThat(event.path("usageAvailable").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"previous_response_id\":\"resp-old\"}", "{\"conversation\":\"conv-old\"}", "{\"input\":[{\"type\":\"item_reference\",\"id\":\"old\"}]}",
            "{\"prompt\":{\"id\":\"stored\"}}", "{\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"unmeasured\"}]}" })
    void responsesHiddenHistoryIsBlockedBeforeDispatch(String input) throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        ObjectNode body = (ObjectNode) mapper.readTree(input);
        body.put("model", MODEL);
        okhttp3.Request request = new okhttp3.Request.Builder().url("http://127.0.0.1:18090/v1/responses")
                .post(okhttp3.RequestBody.create(mapper.writeValueAsString(body), okhttp3.MediaType.get("application/json"))).build();
        okhttp3.Interceptor.Chain chain = org.mockito.Mockito.mock(okhttp3.Interceptor.Chain.class);
        org.mockito.Mockito.when(chain.request()).thenReturn(request);
        try (var ledger = open(mapper)) {
            var telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            try (var scope = telemetry.begin(7L, "run")) {
                assertThatThrownBy(() -> new AtlasLogosBenchmarkInterceptor(telemetry, mapper, clock()).intercept(chain))
                        .isInstanceOf(AtlasLogosBenchmark.DispatchBlockedException.class);
            }
        }
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.never()).proceed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void encryptedHistoryReservationIncludesPriorOutputTokens() throws Exception {
        ObjectMapper mapper = mapper();
        writeEvidence(mapper, 1.0, MODEL);
        String first = "{\"model\":\"gpt-5.6-luna\",\"input\":[]}";
        String next = "{\"model\":\"gpt-5.6-luna\",\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}]}";
        String output = "{\"id\":\"resp-test\",\"model\":\"gpt-5.6-luna\",\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}],\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},\"output_tokens\":20}}";
        okhttp3.Interceptor.Chain chain = org.mockito.Mockito.mock(okhttp3.Interceptor.Chain.class);
        okhttp3.MediaType type = okhttp3.MediaType.get("application/json");
        okhttp3.Request initial = new okhttp3.Request.Builder().url("http://127.0.0.1:18090/v1/responses").post(okhttp3.RequestBody.create(first, type)).build();
        okhttp3.Request continuation = initial.newBuilder().post(okhttp3.RequestBody.create(next, type)).build();
        org.mockito.Mockito.when(chain.request()).thenReturn(initial, continuation);
        org.mockito.Mockito.when(chain.proceed(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> new okhttp3.Response.Builder().request(call.getArgument(0))
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(okhttp3.ResponseBody.create(output, type)).build());
        try (var ledger = open(mapper)) {
            var telemetry = new AtlasLogosBenchmark.Telemetry(ledger, clock());
            var interceptor = new AtlasLogosBenchmarkInterceptor(telemetry, mapper, clock());
            try (var scope = telemetry.begin(7L, "run")) {
                try (var response = interceptor.intercept(chain)) {
                    assertThat(response.code()).isEqualTo(200);
                }
                try (var response = interceptor.intercept(chain)) {
                    assertThat(response.code()).isEqualTo(200);
                }
                scope.complete("completed", false);
            }
        }
        var reservations = Files.readAllLines(evidence.resolve("dispatch-journal.jsonl")).stream().map(line -> {
            try {
                return mapper.readTree(line);
            }
            catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }).filter(row -> "reserve".equals(row.path("type").asText())).toList();
        assertThat(reservations).hasSize(2);
        assertThat(reservations.get(1).path("reservation").path("inputBound").asLong()).isEqualTo(next.length() + 256L + 20L);
    }

    private AtlasLogosBenchmark.Ledger open(ObjectMapper mapper) {
        return AtlasLogosBenchmark.Ledger.open(evidence, mapper, clock());
    }

    private void writeEvidence(ObjectMapper mapper, double budget, String model) throws Exception {
        ObjectNode active = mapper.createObjectNode();
        active.put("schemaVersion", 1).put("campaignId", "campaign").put("mode", "smoke").put("budgetEur", budget).put("startedAt", NOW.toString()).put("status", "running")
                .put("logosHost", "http://127.0.0.1:18090/v1").put("currentCourseId", 7).put("currentObservationId", "observation").put("currentCondition", "condition")
                .put("currentRepetition", 1).put("currentStage", "stage").put("currentInvocationId", "invocation");
        mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
        ObjectNode pricing = mapper.createObjectNode().put("currency", "EUR");
        ObjectNode models = pricing.putObject("models");
        models.putObject(MODEL).put("inputEurPerMillion", "0.17").put("cachedInputEurPerMillion", "0.017").put("cacheWriteInputEurPerMillion", "0.2125")
                .put("outputEurPerMillion", "1.03").put("maxOutputTokens", 128000).put("maxSupportedInputTokens", 272000);
        models.putObject("gpt-5.4-mini").put("inputEurPerMillion", "0.64").put("cachedInputEurPerMillion", "0.064").put("outputEurPerMillion", "3.86")
                .put("maxOutputTokens", 128000).put("maxSupportedInputTokens", 272000);
        mapper.writeValue(evidence.resolve("pricing.json").toFile(), pricing);
        active.put("pricingSha256", AtlasLogosBenchmark.sha256(Files.readAllBytes(evidence.resolve("pricing.json"))));
        mapper.writeValue(evidence.resolve("active-run.json").toFile(), active);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static AtlasLogosBenchmark.Request request() {
        return new AtlasLogosBenchmark.Request("logos", ENDPOINT, MODEL, 1000, "request");
    }

    private static AtlasLogosBenchmark.Attempt response(int status, boolean retryable) {
        return new AtlasLogosBenchmark.Attempt(status, retryable, status == 200 ? "upstream" : null, MODEL, "logos-1", 20, "response", status == 200 ? "completed" : "http_error",
                status == 200 ? null : "HttpStatus" + status);
    }
}

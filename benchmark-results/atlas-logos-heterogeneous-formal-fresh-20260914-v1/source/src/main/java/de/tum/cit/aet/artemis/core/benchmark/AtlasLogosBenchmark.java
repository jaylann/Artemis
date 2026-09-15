package de.tum.cit.aet.artemis.core.benchmark;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Atlas Logos benchmark ledger, cost guard, and thread-local evidence facade.
 *
 * <p>
 * This package is profile-only infrastructure. It observes the existing Spring AI OpenAI HTTP
 * client and writes prompt-free JSONL records; it does not implement a model loop, retry policy,
 * tool wrapper, or benchmark endpoint.
 * </p>
 */
public final class AtlasLogosBenchmark {

    /** Exact Spring profile used by the benchmark runner. */
    public static final String PROFILE = "atlas-logos-benchmark";

    /** Environment variable naming the evidence directory. */
    public static final String EVIDENCE_ENV = "ATLAS_BENCHMARK_EVIDENCE_DIR";

    /** One-byte-per-token bound plus provider/request wrapper overhead. */
    static final long INPUT_OVERHEAD_BYTES = 256L;

    /** Verified standard-tier input limit for the supplied pricing snapshot. */
    static final long MAX_STANDARD_INPUT = 272_000L;

    private AtlasLogosBenchmark() {
    }

    /** @return configured absolute evidence directory, or fail closed when absent */
    public static Path evidenceDirectory() {
        String value = System.getenv(EVIDENCE_ENV);
        if (value == null || value.isBlank()) {
            value = System.getProperty(EVIDENCE_ENV);
        }
        if (value == null || value.isBlank()) {
            throw blocked(EVIDENCE_ENV + " must be set for the Atlas Logos benchmark");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    /** @return lowercase SHA-256 over bytes, without retaining the bytes */
    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new BenchmarkException("SHA-256 is unavailable", ex);
        }
    }

    /** A benchmark-local exception that is safe to expose to logs. */
    public static class BenchmarkException extends RuntimeException {

        BenchmarkException(String message) {
            super(message);
        }

        BenchmarkException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A dispatch safety failure; callers must stop the current experiment. */
    public static final class DispatchBlockedException extends BenchmarkException {

        DispatchBlockedException(String message) {
            super(message);
        }
    }

    /** Immutable runner metadata loaded from active-run.json. */
    record ActiveRun(String runId, String campaignId, String observationId, String condition, Integer repetition, String stage, Long courseId, String logosHost, String status,
            String mode, Instant startedAt, double smokeBudgetEur, double campaignBudgetEur, boolean interrupted, String currentInvocationId) {

        /** @return whether a request host matches the authorized Logos gateway host */
        boolean allows(URI endpoint) {
            if (endpoint == null || logosHost == null || logosHost.isBlank() || !logosHost.contains("://")) {
                return false;
            }
            URI expected;
            try {
                expected = URI.create(logosHost);
            }
            catch (IllegalArgumentException ex) {
                return false;
            }
            String prefix = expected.getPath() == null || expected.getPath().isBlank() ? "/" : expected.getPath().replaceAll("/$", "") + "/";
            return expected.getScheme().equalsIgnoreCase(endpoint.getScheme()) && expected.getRawAuthority().equalsIgnoreCase(endpoint.getRawAuthority())
                    && (endpoint.getPath().equals(expected.getPath()) || endpoint.getPath().startsWith(prefix));
        }
    }

    /** Immutable verified rate snapshot in EUR per million tokens. */
    record ModelPrice(double input, double cachedInput, Double cacheWriteInput, double output, long maximumOutput, long maximumInput, String aliasOf,
            LongContextPrice longContext) {

        public ModelPrice {
            if (!Double.isFinite(input) || input < 0 || !Double.isFinite(cachedInput) || cachedInput < 0
                    || cacheWriteInput != null && (!Double.isFinite(cacheWriteInput) || cacheWriteInput < 0) || !Double.isFinite(output) || output < 0 || maximumOutput <= 0
                    || maximumInput <= 0 || cachedInput > input || aliasOf != null && aliasOf.isBlank() || maximumInput > MAX_STANDARD_INPUT && longContext == null
                    || longContext != null && longContext.threshold >= maximumInput) {
                throw blocked("pricing.json contains invalid model rates");
            }
        }

        double cost(long inputTokens, long cachedTokens, long cacheWriteTokens, long outputTokens) {
            if (inputTokens < 0 || cachedTokens < 0 || cacheWriteTokens < 0 || cachedTokens > inputTokens || cacheWriteTokens > inputTokens - cachedTokens || outputTokens < 0
                    || cacheWriteTokens > 0 && cacheWriteInput == null) {
                throw blocked("provider returned invalid token usage");
            }
            double inputMultiplier = longContext != null && inputTokens > longContext.threshold ? longContext.inputMultiplier : 1;
            double outputMultiplier = longContext != null && inputTokens > longContext.threshold ? longContext.outputMultiplier : 1;
            return (((inputTokens - cachedTokens - cacheWriteTokens) * input + cachedTokens * cachedInput + cacheWriteTokens * (cacheWriteInput == null ? 0 : cacheWriteInput))
                    * inputMultiplier + outputTokens * output * outputMultiplier) / 1_000_000d;
        }

        double upperBound(long inputTokens) {
            double inputRate = cacheWriteInput == null ? input : Math.max(input, cacheWriteInput);
            double inputMultiplier = longContext != null && inputTokens > longContext.threshold ? longContext.inputMultiplier : 1;
            double outputMultiplier = longContext != null && inputTokens > longContext.threshold ? longContext.outputMultiplier : 1;
            return (inputTokens * inputRate * inputMultiplier + maximumOutput * output * outputMultiplier) / 1_000_000d;
        }
    }

    /** Verified whole-request price uplift above a token threshold; bounds must remain monotone. */
    record LongContextPrice(long threshold, double inputMultiplier, double outputMultiplier) {

        LongContextPrice {
            if (threshold <= 0 || !Double.isFinite(inputMultiplier) || inputMultiplier < 1 || !Double.isFinite(outputMultiplier) || outputMultiplier < 1) {
                throw blocked("pricing.json contains invalid long-context rates");
            }
        }
    }

    /** Prompt-free request facts retained for accounting and hashing. */
    record Request(String provider, URI endpoint, String model, long bytes, String sha256, String reasoningEffort, Integer sdkRetryCount) {

        Request(String provider, URI endpoint, String model, long bytes, String sha256) {
            this(provider, endpoint, model, bytes, sha256, null, null);
        }
    }

    /** Provider usage extracted from a response body; null tokens mean unavailable. */
    record Usage(Long input, Long cachedInput, Long cacheWrite, Long output, Long reasoning, String responseId, String returnedModel, Integer toolCalls) {

        Usage(Long input, Long cachedInput, Long output, Long reasoning, String responseId, String returnedModel, Integer toolCalls) {
            this(input, cachedInput, 0L, output, reasoning, responseId, returnedModel, toolCalls);
        }

        static Usage unavailable(String responseId, String model) {
            return new Usage(null, null, null, null, null, responseId, model, null);
        }

        static Usage parse(JsonNode root, String responseId, String model) {
            JsonNode usage = root == null ? null : root.get("usage");
            if (usage == null || !usage.isObject() || hasUnsupportedCacheVariant(usage)) {
                return unavailable(responseId, model);
            }
            Long input = number(usage, "prompt_tokens", "input_tokens");
            Long cached = number(usage.path("prompt_tokens_details"), "cached_tokens");
            if (cached == null) {
                cached = number(usage.path("input_tokens_details"), "cached_tokens");
            }
            Long output = number(usage, "completion_tokens", "output_tokens");
            Long reasoning = number(usage.path("completion_tokens_details"), "reasoning_tokens");
            if (reasoning == null) {
                reasoning = number(usage.path("output_tokens_details"), "reasoning_tokens");
            }
            TokenField cacheWrite = cacheWrite(usage);
            if ("gpt-5.6-luna".equals(model) && !cacheWrite.present) {
                return unavailable(responseId, model);
            }
            if (input == null || cached == null || cacheWrite.value == null || output == null || input < 0 || cached < 0 || cacheWrite.value < 0 || output < 0 || cached > input
                    || cacheWrite.value > input - cached || reasoning != null && (reasoning < 0 || reasoning > output)) {
                return unavailable(responseId, model);
            }
            return new Usage(input, cached, cacheWrite.value, output, reasoning, responseId, model, toolCalls(root));
        }

        boolean available() {
            return input != null && cachedInput != null && cacheWrite != null && output != null;
        }

        long reasoningOrZero() {
            return reasoning == null ? 0 : reasoning;
        }

        private static Long number(JsonNode node, String... names) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            for (String name : names) {
                JsonNode value = node.get(name);
                if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
                    return value.asLong();
                }
            }
            return null;
        }

        private record TokenField(boolean present, Long value) {
        }

        private static TokenField cacheWrite(JsonNode usage) {
            Long value = 0L;
            boolean present = false;
            for (String name : List.of("input_tokens_details", "prompt_tokens_details")) {
                JsonNode details = usage.get(name);
                if (details == null || details.isNull() || !details.isObject() || !details.has("cache_write_tokens")) {
                    continue;
                }
                JsonNode cacheWrite = details.get("cache_write_tokens");
                if (present || !cacheWrite.isIntegralNumber() || !cacheWrite.canConvertToLong()) {
                    return new TokenField(true, null);
                }
                present = true;
                value = cacheWrite.asLong();
            }
            return new TokenField(present, value);
        }

        private static boolean hasUnsupportedCacheVariant(JsonNode usage) {
            if (usage.has("cache_write_tokens")) {
                return true;
            }
            for (JsonNode node : List.of(usage, usage.path("input_tokens_details"), usage.path("prompt_tokens_details"))) {
                if (node.has("cache_write") || node.has("cache_write_input_tokens") || node.has("cache_creation_input_tokens") || node.has("cache_creation")) {
                    return true;
                }
            }
            return false;
        }

        private static int toolCalls(JsonNode root) {
            JsonNode choices = root == null ? null : root.get("choices");
            if (choices != null && choices.isArray()) {
                int count = 0;
                for (JsonNode choice : choices) {
                    JsonNode calls = choice.path("message").path("tool_calls");
                    if (calls.isArray()) {
                        count += calls.size();
                    }
                }
                return count;
            }
            JsonNode output = root == null ? null : root.get("output");
            if (output != null && output.isArray()) {
                int count = 0;
                for (JsonNode item : output) {
                    if ("function_call".equals(item.path("type").asText())) {
                        count++;
                    }
                }
                return count;
            }
            return 0;
        }
    }

    /** Response facts used to record one attempt. */
    record Attempt(Integer httpStatus, boolean retryable, String responseId, String returnedModel, String logosRequestId, long bytes, String sha256, String status,
            String errorClass) {
    }

    /** Durable reservation held from pre-dispatch until measured settlement. */
    record Reservation(String id, String runId, String invocationId, String campaignId, int attempt, String model, long inputBound, long maximumOutput, double reservedCostEur,
            Instant startedAt) {
    }

    /** Actual charged cost after a usage-bearing response. */
    record Settlement(double costEur, long reasoningTokens) {
    }

    /** Canonical provider-events.jsonl record. */
    record ProviderEvent(int schemaVersion, String eventType, String eventId, long sequence, String campaignId, String observationId, String condition, Integer repetition,
            String stage, String invocationId, int attempt, String phase, String provider, String requestedModel, String returnedModel, String responseId, Instant startedAt,
            Instant endedAt, long durationMs, String status, Integer httpStatus, boolean success, boolean retryable, Long inputTokens, Long cachedInputTokens,
            Long cacheWriteInputTokens, Long outputTokens, boolean usageAvailable, Integer toolCallCount, String requestSha256, String responseSha256, String errorClass,
            long requestBytes, long responseBytes, Long reasoningTokens, Double costEur, Double reservedCostEur, String logosRequestId, String reasoningEffort,
            Integer sdkRetryCount) {

    }

    /** Canonical terminal invocation-events.jsonl record. */
    record InvocationEvent(int schemaVersion, String eventType, String eventId, long sequence, String campaignId, String observationId, String condition, Integer repetition,
            String stage, String invocationId, String trigger, Instant startedAt, Instant endedAt, long durationMs, String status, String terminalStatus,
            List<String> providerAttemptIds, int toolCallCount, boolean usageComplete, Double costEur, String costStatus, boolean interrupted, Map<String, Object> details) {

        public InvocationEvent {
            providerAttemptIds = List.copyOf(providerAttemptIds);
        }

    }

    /** Single-process, append-only dispatch journal; pending reservations survive interruption. */
    public static final class Ledger implements Closeable {

        private ActiveRun run;

        private final Map<String, ModelPrice> prices;

        private final Path activeRunFile;

        private final Path stopFile;

        private final ObjectMapper mapper;

        private final Clock clock;

        private final FileChannel journal;

        private final java.nio.channels.FileLock processLock;

        private final FileChannel invocations;

        private final FileChannel providers;

        private final Map<String, Reservation> reservations = new HashMap<>();

        private final Set<String> recordedProviderAttempts = new HashSet<>();

        private final java.util.Set<String> terminalInvocations = new java.util.HashSet<>();

        private long sequence;

        private double committed;

        private boolean startupPending;

        private boolean blocked;

        /** Opens the frozen prices and replays the dispatch journal without resetting spending. */
        static Ledger open(Path directory, ObjectMapper mapper, Clock clock) {
            try {
                return new Ledger(directory, mapper, clock);
            }
            catch (IOException ex) {
                throw new BenchmarkException("could not open benchmark ledger", ex);
            }
        }

        private Ledger(Path directory, ObjectMapper mapper, Clock clock) throws IOException {
            Files.createDirectories(directory);
            this.mapper = mapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            this.clock = clock;
            activeRunFile = directory.resolve("active-run.json");
            stopFile = directory.resolve("STOP");
            run = readRun(activeRunFile, mapper);
            Path pricingPath = directory.resolve("pricing.json");
            String expectedPricing = mapper.readTree(Files.readString(activeRunFile)).path("pricingSha256").asText();
            if (!sha256(Files.readAllBytes(pricingPath)).equals(expectedPricing)) {
                throw blocked("frozen pricing hash differs from active-run.json");
            }
            prices = readPrices(pricingPath, mapper);
            Path journalPath = directory.resolve("dispatch-journal.jsonl");
            if (!Files.exists(journalPath) && (Files.exists(directory.resolve("provider-events.jsonl")) || Files.exists(directory.resolve("invocation-events.jsonl")))) {
                throw blocked("existing evidence has no dispatch journal");
            }
            journal = FileChannel.open(journalPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            processLock = journal.tryLock();
            if (processLock == null) {
                journal.close();
                throw blocked("another Artemis process owns this evidence directory");
            }
            for (String line : Files.readAllLines(journalPath)) {
                JsonNode event = mapper.readTree(line);
                if (!run.campaignId.equals(event.path("campaignId").asText())) {
                    throw blocked("dispatch journal belongs to another campaign");
                }
                String id = event.path("attemptId").asText();
                if ("reserve".equals(event.path("type").asText())) {
                    if (reservations.put(id, mapper.treeToValue(event.get("reservation"), Reservation.class)) != null) {
                        throw blocked("duplicate dispatch reservation");
                    }
                }
                else if ("settle".equals(event.path("type").asText()) && reservations.remove(id) != null) {
                    committed += requiredDecimal(event, "costEur");
                }
                else {
                    throw blocked("invalid dispatch journal transition");
                }
            }
            Path invocationPath = directory.resolve("invocation-events.jsonl");
            Path providerPath = directory.resolve("provider-events.jsonl");
            for (Path path : List.of(invocationPath, providerPath)) {
                if (Files.exists(path)) {
                    for (String line : Files.readAllLines(path)) {
                        JsonNode event = mapper.readTree(line);
                        sequence = Math.max(sequence, event.path("sequence").asLong());
                        if ("provider_attempt".equals(event.path("eventType").asText())) {
                            recordedProviderAttempts.add(event.path("eventId").asText());
                        }
                        if ("invocation_terminal".equals(event.path("eventType").asText())) {
                            terminalInvocations.add(event.path("invocationId").asText());
                        }
                    }
                }
            }
            startupPending = hasUncapturedReservations();
            invocations = FileChannel.open(invocationPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            providers = FileChannel.open(providerPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }

        synchronized Reservation reserve(Request request, String invocationId, int attempt) {
            refreshRun();
            if (Files.exists(stopFile) || !"running".equals(run.status) || run.interrupted || !invocationId.equals(run.currentInvocationId) || !run.allows(request.endpoint)
                    || startupPending || terminalInvocations.contains(invocationId)) {
                throw blocked("dispatch is outside the active invocation or has unresolved prior evidence");
            }
            if (blocked || reservations.values().stream().anyMatch(value -> !recordedProviderAttempts.contains(value.id) && !value.invocationId.equals(invocationId))) {
                throw blocked("dispatch is halted after incomplete usage");
            }
            ModelPrice price = requirePrice(request.model, request.model);
            long inputBound;
            try {
                inputBound = Math.addExact(request.bytes, INPUT_OVERHEAD_BYTES);
            }
            catch (ArithmeticException ex) {
                throw hardBlock("request exceeds verified input pricing bounds");
            }
            if (inputBound > price.maximumInput && price.longContext == null) {
                throw hardBlock("request exceeds verified input pricing bounds");
            }
            // A byte bound is not an actual token count. With verified pricing for the full
            // model context, reserve at most that physical input maximum, including its tier.
            // The provider may reject oversized input; every attempted request still gets recorded.
            inputBound = Math.min(inputBound, price.maximumInput);
            double upper = Math.nextUp(price.upperBound(inputBound));
            double reserved = reservations.values().stream().mapToDouble(Reservation::reservedCostEur).sum();
            if (!Double.isFinite(upper)) {
                throw hardBlock("request cost bound is not finite");
            }
            if (committed + reserved + upper > cap()) {
                stop("budget exhausted before dispatch");
                throw blocked("budget exhausted before dispatch");
            }
            Reservation reservation = new Reservation(UUID.randomUUID().toString(), run.runId, invocationId, run.campaignId, attempt, request.model, inputBound,
                    price.maximumOutput, upper, clock.instant());
            journal("reserve", reservation, null);
            reservations.put(reservation.id, reservation);
            return reservation;
        }

        synchronized Settlement settle(Reservation reservation, Usage usage) {
            if (!reservations.containsKey(reservation.id)) {
                throw blocked("reservation missing or already settled");
            }
            if (!usage.available()) {
                // Retain the reservation. Native retries in this invocation may reserve again.
                throw blocked("provider response has incomplete usage");
            }
            ModelPrice price = requirePrice(reservation.model, usage.returnedModel);
            double actual;
            try {
                actual = price.cost(usage.input, usage.cachedInput, usage.cacheWrite, usage.output);
            }
            catch (DispatchBlockedException ex) {
                throw hardBlock(ex.getMessage() == null ? "provider returned invalid token usage" : ex.getMessage());
            }
            if (usage.input > price.maximumInput || usage.output > price.maximumOutput || !Double.isFinite(actual) || actual > reservation.reservedCostEur) {
                throw hardBlock("usage exceeds verified reservation bounds");
            }
            journal("settle", reservation, actual);
            reservations.remove(reservation.id);
            committed += actual;
            return new Settlement(actual, usage.reasoningOrZero());
        }

        private void journal(String type, Reservation reservation, Double cost) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", type).put("campaignId", run.campaignId).put("attemptId", reservation.id);
            if (cost == null) {
                event.set("reservation", mapper.valueToTree(reservation));
            }
            else {
                event.put("costEur", cost);
            }
            append(journal, event, mapper);
        }

        synchronized void appendProvider(ProviderEvent event) {
            appendEvent(providers, event);
            recordedProviderAttempts.add(event.eventId);
            startupPending = startupPending && hasUncapturedReservations();
        }

        synchronized void appendInvocation(InvocationEvent event) {
            appendEvent(invocations, event);
            terminalInvocations.add(event.invocationId);
        }

        private void appendEvent(FileChannel channel, Object event) {
            ObjectNode json = mapper.valueToTree(event);
            json.put("sequence", ++sequence);
            append(channel, json, mapper);
        }

        synchronized void refresh() {
            refreshRun();
        }

        synchronized boolean terminal(String invocationId) {
            return terminalInvocations.contains(invocationId);
        }

        private void refreshRun() {
            try {
                ActiveRun latest = readRun(activeRunFile, mapper);
                if (!run.campaignId.equals(latest.campaignId) || !run.logosHost.equals(latest.logosHost) || run.campaignBudgetEur != latest.campaignBudgetEur) {
                    throw blocked("active campaign configuration changed");
                }
                run = latest;
            }
            catch (IOException ex) {
                throw new BenchmarkException("could not read active-run.json", ex);
            }
        }

        private double cap() {
            return Math.min(run.campaignBudgetEur, "smoke".equals(run.mode) ? 1.0 : 20.0);
        }

        private boolean hasUncapturedReservations() {
            return reservations.keySet().stream().anyMatch(id -> !recordedProviderAttempts.contains(id));
        }

        private ModelPrice requirePrice(String requested, String returned) {
            ModelPrice price = returned == null ? null : prices.get(returned);
            if (price == null || !requested.equals(returned) && !requested.equals(price.aliasOf)) {
                throw hardBlock("model identity has no matching verified price");
            }
            return price;
        }

        private DispatchBlockedException hardBlock(String message) {
            blocked = true;
            stop(message);
            return blocked(message);
        }

        private void stop(String reason) {
            try {
                Files.writeString(stopFile, reason + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            catch (IOException ex) {
                throw new BenchmarkException("could not write benchmark STOP marker", ex);
            }
        }

        @Override
        public void close() {
            try {
                invocations.close();
                providers.close();
                processLock.release();
                journal.close();
            }
            catch (IOException ex) {
                throw new BenchmarkException("could not close benchmark evidence", ex);
            }
        }

        private static ActiveRun readRun(Path path, ObjectMapper mapper) throws IOException {
            JsonNode root = mapper.readTree(Files.readString(path));
            String campaign = requiredText(root, "campaignId");
            String mode = requiredText(root, "mode");
            double budget = requiredDecimal(root, "budgetEur");
            if (budget <= 0 || !List.of("smoke", "formal").contains(mode)) {
                throw blocked("invalid campaign mode or budget");
            }
            return new ActiveRun(campaign, campaign, root.path("currentObservationId").asText(""), root.path("currentCondition").asText(""), integer(root, "currentRepetition"),
                    root.path("currentStage").asText(""), longValue(root, "currentCourseId"), requiredText(root, "logosHost"), requiredText(root, "status"), mode,
                    Instant.parse(requiredText(root, "startedAt")), Math.min(1.0, budget), budget, root.path("interrupted").asBoolean(false),
                    root.path("currentInvocationId").asText(""));
        }

        private static Map<String, ModelPrice> readPrices(Path path, ObjectMapper mapper) throws IOException {
            JsonNode root = mapper.readTree(Files.readString(path));
            if (!"EUR".equals(root.path("currency").asText()) || !root.path("models").isObject() || root.path("models").isEmpty()) {
                throw blocked("pricing.json must contain verified EUR model prices");
            }
            Map<String, ModelPrice> prices = new HashMap<>();
            root.path("models").fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                JsonNode tier = value.get("longContext");
                LongContextPrice longContext = tier == null ? null
                        : new LongContextPrice(requiredLong(tier, "thresholdTokens"), requiredDecimal(tier, "inputMultiplier"), requiredDecimal(tier, "outputMultiplier"));
                prices.put(field.getKey(),
                        new ModelPrice(requiredDecimal(value, "inputEurPerMillion"), requiredDecimal(value, "cachedInputEurPerMillion"),
                                optionalDecimal(value, "cacheWriteInputEurPerMillion"), requiredDecimal(value, "outputEurPerMillion"), requiredLong(value, "maxOutputTokens"),
                                requiredLong(value, "maxSupportedInputTokens"), text(value, "aliasOf"), longContext));
            });
            return prices;
        }
    }

    /** Thread-local phase/invocation facade consumed by the AOP boundary and HTTP interceptor. */
    public static final class Telemetry {

        private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

        private final Ledger ledger;

        private final Clock clock;

        /** Creates a facade from the runner evidence directory. */
        static Telemetry fromEnvironment(ObjectMapper mapper) {
            Clock clock = Clock.systemUTC();
            return new Telemetry(Ledger.open(evidenceDirectory(), mapper, clock), clock);
        }

        Telemetry(Ledger ledger, Clock clock) {
            this.ledger = ledger;
            this.clock = clock;
        }

        /**
         * Starts or joins an invocation and verifies the runner's expected course.
         *
         * @param courseId expected course, when available at the entry point
         * @param trigger  normal application entry point
         * @return scope recording the terminal invocation outcome
         */
        public Scope begin(Long courseId, String trigger) {
            if (ledger == null) {
                return Scope.noop();
            }
            ledger.refresh();
            if (courseId != null && ledger.run.courseId != null && !courseId.equals(ledger.run.courseId)) {
                throw blocked("invocation course is outside active benchmark run");
            }
            Frame existing = CURRENT.get();
            if (existing != null) {
                return new Scope(this, existing.context, false);
            }
            String invocationId = ledger.run.currentInvocationId.isBlank() ? UUID.randomUUID().toString() : ledger.run.currentInvocationId;
            if (ledger.terminal(invocationId)) {
                throw blocked("active observation already has a terminal invocation event");
            }
            Context context = new Context(invocationId, trigger, ledger.run, clock.instant());
            CURRENT.set(new Frame(context, "orchestration"));
            return new Scope(this, context, true);
        }

        /**
         * Enters a nested phase; only a safe label is retained.
         *
         * @param value phase label
         * @return scope restoring the previous phase on close
         */
        public Phase phase(String value) {
            Frame frame = CURRENT.get();
            if (ledger == null || frame == null) {
                return Phase.noop();
            }
            if (value == null || value.isBlank() || value.length() > 80 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid benchmark phase");
            }
            String previous = frame.phase;
            frame.phase = value;
            if (value.equals("tool")) {
                frame.context.toolCalls.incrementAndGet();
            }
            return new Phase(frame, previous);
        }

        /**
         * Adds redacted application outcomes and authoritative callback counts to the current invocation.
         *
         * @param values redacted invocation metadata
         */
        public void details(Map<String, Object> values) {
            Frame frame = CURRENT.get();
            if (frame != null) {
                frame.context.details.putAll(values);
                if (values.get("toolCallCount") instanceof Number count) {
                    frame.context.toolCalls.set(count.intValue());
                }
            }
        }

        /** Reserves one HTTP attempt before OkHttp proceeds. */
        Reservation reserve(URI endpoint, String model, long bytes, String hash) {
            Frame frame = requireFrame();
            return ledger.reserve(new Request("logos", endpoint, model, bytes, hash), frame.context.id, frame.context.attempts.incrementAndGet());
        }

        /** Settles and records one provider attempt, preserving an unresolved reservation on block. */
        Settlement complete(Reservation reservation, Request request, Attempt response, Usage usage, Instant startedAt) {
            return complete(reservation, request, response, usage, startedAt, false);
        }

        /**
         * Records an attempt. Retryable transport failures may return to the unchanged SDK retry
         * loop while their conservative reservation remains in the ledger.
         */
        Settlement complete(Reservation reservation, Request request, Attempt response, Usage usage, Instant startedAt, boolean retryableFailure) {
            Frame frame = requireFrame();
            Instant ended = clock.instant();
            Settlement settlement = null;
            DispatchBlockedException failure = null;
            try {
                settlement = ledger.settle(reservation, usage);
                frame.context.cost += settlement.costEur;
            }
            catch (DispatchBlockedException ex) {
                frame.context.usageComplete = false;
                failure = ex;
            }
            boolean available = usage.available() && failure == null;
            ProviderEvent event = new ProviderEvent(1, "provider_attempt", reservation.id, 0, frame.context.run.campaignId, frame.context.run.observationId,
                    frame.context.run.condition, frame.context.run.repetition, frame.context.run.stage, frame.context.id, reservation.attempt, frame.phase, "logos", request.model,
                    response.returnedModel, response.responseId, startedAt, ended, duration(startedAt, ended), response.status, response.httpStatus,
                    failure == null && response.httpStatus != null && response.httpStatus >= 200 && response.httpStatus < 300 && available, response.retryable, usage.input,
                    usage.cachedInput, usage.cacheWrite, usage.output, available, usage.toolCalls, request.sha256, response.sha256, response.errorClass, request.bytes,
                    response.bytes, usage.reasoning, settlement == null ? null : settlement.costEur, reservation.reservedCostEur, response.logosRequestId, request.reasoningEffort,
                    request.sdkRetryCount);
            ledger.appendProvider(event);
            frame.context.providerIds.add(event.eventId);
            if (failure != null && usage.available()) {
                throw failure;
            }
            return settlement;
        }

        private Frame requireFrame() {
            if (ledger == null || CURRENT.get() == null) {
                throw blocked("provider dispatch is outside active benchmark invocation");
            }
            return CURRENT.get();
        }

        private void close(Context context, boolean owner, String status, boolean interrupted) {
            if (!owner || context.closed) {
                return;
            }
            context.closed = true;
            Instant ended = clock.instant();
            String terminal = interrupted ? "interrupted" : status;
            ledger.appendInvocation(new InvocationEvent(1, "invocation_terminal", UUID.randomUUID().toString(), 0, context.run.campaignId, context.run.observationId,
                    context.run.condition, context.run.repetition, context.run.stage, context.id, context.trigger, context.startedAt, ended, duration(context.startedAt, ended),
                    terminal, terminal, context.providerIds, context.toolCalls.get(), context.usageComplete, context.usageComplete ? context.cost : null,
                    context.usageComplete ? "complete" : "blocked", interrupted, Map.copyOf(context.details)));
            CURRENT.remove();
        }

        /** Invocation scope; close exactly once at the orchestration boundary. */
        public static final class Scope implements AutoCloseable {

            private final Telemetry telemetry;

            private final Context context;

            private final boolean owner;

            private String status = "interrupted";

            private boolean interrupted;

            private boolean closed;

            private Scope(Telemetry telemetry, Context context, boolean owner) {
                this.telemetry = telemetry;
                this.context = context;
                this.owner = owner;
            }

            private static Scope noop() {
                return new Scope(null, null, false);
            }

            /**
             * Sets terminal status before closing.
             *
             * @param status      application terminal status
             * @param interrupted whether execution was interrupted
             */
            public void complete(String status, boolean interrupted) {
                this.status = status;
                this.interrupted = interrupted;
            }

            @Override
            public void close() {
                if (!closed && telemetry != null) {
                    closed = true;
                    telemetry.close(context, owner, status, interrupted);
                }
            }
        }

        /** Nested phase scope. */
        public static final class Phase implements AutoCloseable {

            private final Frame frame;

            private final String previous;

            private boolean closed;

            private Phase(Frame frame, String previous) {
                this.frame = frame;
                this.previous = previous;
            }

            private static Phase noop() {
                return new Phase(null, null);
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    if (frame != null) {
                        frame.phase = previous;
                    }
                }
            }
        }

        private static final class Frame {

            private final Context context;

            private String phase;

            private Frame(Context context, String phase) {
                this.context = context;
                this.phase = phase;
            }
        }

        private static final class Context {

            private final String id;

            private final String trigger;

            private final ActiveRun run;

            private final Instant startedAt;

            private final AtomicInteger attempts = new AtomicInteger();

            private final AtomicInteger toolCalls = new AtomicInteger();

            private final Map<String, Object> details = new java.util.concurrent.ConcurrentHashMap<>();

            private final List<String> providerIds = java.util.Collections.synchronizedList(new ArrayList<>());

            private boolean usageComplete = true;

            private double cost;

            private boolean closed;

            private Context(String id, String trigger, ActiveRun run, Instant startedAt) {
                this.id = id;
                this.trigger = trigger;
                this.run = run;
                this.startedAt = startedAt;
            }
        }
    }

    private static DispatchBlockedException blocked(String message) {
        return new DispatchBlockedException(message);
    }

    private static long duration(Instant start, Instant end) {
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String requiredText(JsonNode root, String name) {
        String value = text(root, name);
        if (value == null || value.isBlank()) {
            throw blocked("missing " + name);
        }
        return value;
    }

    private static Optional<Long> optionalLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? Optional.of(value.asLong()) : Optional.empty();
    }

    private static Long longValue(JsonNode root, String name) {
        return optionalLong(root, name).orElse(null);
    }

    private static Integer integer(JsonNode root, String name) {
        return optionalLong(root, name).map(Long::intValue).orElse(null);
    }

    private static double decimal(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !(value.isNumber() || value.isTextual())) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value.asText());
        }
        catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static double requiredDecimal(JsonNode node, String name) {
        double value = decimal(node, name);
        if (!Double.isFinite(value) || value < 0) {
            throw blocked("pricing.json is missing verified decimal rate");
        }
        return value;
    }

    private static Double optionalDecimal(JsonNode node, String name) {
        return node.has(name) ? decimal(node, name) : null;
    }

    private static long requiredLong(JsonNode node, String name) {
        return optionalLong(node, name).filter(value -> value > 0).orElseThrow(() -> blocked("pricing.json is missing maximum output metadata"));
    }

    private static void append(FileChannel channel, Object value, ObjectMapper mapper) {
        try {
            byte[] bytes = (mapper.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        catch (IOException ex) {
            throw new BenchmarkException("could not append benchmark evidence", ex);
        }
    }
}

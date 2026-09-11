package de.tum.cit.aet.artemis.core.benchmark;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * Passive OkHttp hook for the existing Spring AI OpenAI client. The official SDK retry wrapper
 * remains untouched, so every SDK attempt enters this interceptor and receives its own reservation.
 */
public final class AtlasLogosBenchmarkInterceptor implements Interceptor {

    private final AtlasLogosBenchmark.Telemetry telemetry;

    private final ObjectMapper mapper;

    private final Clock clock;

    // Hashes only: encrypted reasoning may compress billable tokens, so bytes alone are not a safe bound.
    private final Map<String, Long> reasoningTokenBounds = new ConcurrentHashMap<>();

    /** Creates a production interceptor. */
    public AtlasLogosBenchmarkInterceptor(AtlasLogosBenchmark.Telemetry telemetry, ObjectMapper mapper) {
        this(telemetry, mapper, Clock.systemUTC());
    }

    /** Creates an interceptor with a test clock. */
    public AtlasLogosBenchmarkInterceptor(AtlasLogosBenchmark.Telemetry telemetry, ObjectMapper mapper, Clock clock) {
        this.telemetry = telemetry;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        byte[] requestBytes = bodyBytes(original.body());
        JsonNode requestJson = parse(requestBytes);
        String model = text(requestJson, "model");
        if (model == null || model.isBlank()) {
            throw new AtlasLogosBenchmark.DispatchBlockedException("provider request has no explicit model");
        }
        validateRequest(requestJson);
        Request replayable = original.newBuilder().method(original.method(), RequestBody.create(requestBytes, original.body() == null ? null : original.body().contentType()))
                .build();
        URI endpoint = URI.create(original.url().toString());
        String requestHash = AtlasLogosBenchmark.sha256(requestBytes);
        String retryHeader = original.header("x-stainless-retry-count");
        Integer retryCount = retryHeader != null && retryHeader.matches("[0-9]{1,6}") ? Integer.valueOf(retryHeader) : null;
        AtlasLogosBenchmark.Request request = new AtlasLogosBenchmark.Request("logos", endpoint, model, requestBytes.length, requestHash,
                requestJson.has("reasoning") ? text(requestJson.path("reasoning"), "effort") : text(requestJson, "reasoning_effort"), retryCount);
        Instant started = clock.instant();
        AtlasLogosBenchmark.Reservation reservation = telemetry.reserve(endpoint, model, inputBound(requestJson, requestBytes.length), requestHash);
        try {
            Response response = chain.proceed(replayable);
            okhttp3.MediaType responseType = response.body() == null ? null : response.body().contentType();
            byte[] responseBytes = response.body() == null ? new byte[0] : response.body().bytes();
            JsonNode responseJson = parse(responseBytes);
            String responseId = text(responseJson, "id");
            String returnedModel = text(responseJson, "model");
            String logosRequestId = response.header("X-Request-ID");
            AtlasLogosBenchmark.Usage usage = AtlasLogosBenchmark.Usage.parse(responseJson, responseId, returnedModel);
            AtlasLogosBenchmark.Attempt facts = new AtlasLogosBenchmark.Attempt(response.code(), retryable(response.code()), responseId, returnedModel, logosRequestId,
                    responseBytes.length, AtlasLogosBenchmark.sha256(responseBytes), response.isSuccessful() ? "completed" : "http_error",
                    response.isSuccessful() ? null : "HttpStatus" + response.code());
            try {
                telemetry.complete(reservation, request, facts, usage, started, facts.retryable());
            }
            catch (AtlasLogosBenchmark.DispatchBlockedException ex) {
                response.close();
                throw ex;
            }
            if (usage.available()) {
                for (JsonNode item : responseJson.path("output")) {
                    if ("reasoning".equals(item.path("type").asText()) && item.hasNonNull("encrypted_content")) {
                        reasoningTokenBounds.merge(reasoningHash(item), usage.output(), Math::max);
                    }
                }
            }
            return response.newBuilder().body(ResponseBody.create(responseBytes, responseType)).build();
        }
        catch (AtlasLogosBenchmark.DispatchBlockedException ex) {
            throw ex;
        }
        catch (IOException ex) {
            AtlasLogosBenchmark.Attempt facts = new AtlasLogosBenchmark.Attempt(null, true, null, null, null, 0, AtlasLogosBenchmark.sha256(new byte[0]), "transport_error",
                    ex.getClass().getSimpleName());
            telemetry.complete(reservation, request, facts, AtlasLogosBenchmark.Usage.unavailable(null, null), started, true);
            throw ex;
        }
    }

    /** Includes a conservative token allowance for reasoning generated in earlier measured rounds. */
    private long inputBound(JsonNode request, long bytes) {
        long bound = bytes;
        for (JsonNode item : request.path("input")) {
            if ("reasoning".equals(item.path("type").asText())) {
                Long tokens = reasoningTokenBounds.get(reasoningHash(item));
                if (tokens == null) {
                    throw new AtlasLogosBenchmark.DispatchBlockedException("unmeasured reasoning history cannot be priced");
                }
                // Entire prior output is an upper bound even when reasoning_tokens is unavailable.
                bound = Math.addExact(bound, tokens);
            }
        }
        return bound;
    }

    private static String reasoningHash(JsonNode item) {
        return AtlasLogosBenchmark.sha256(item.path("encrypted_content").asText().getBytes(StandardCharsets.UTF_8));
    }

    private void validateRequest(JsonNode request) {
        if (request.path("stream").asBoolean(false) || containsNonTextPart(request)) {
            throw new AtlasLogosBenchmark.DispatchBlockedException("benchmark pricing only covers non-streaming text requests");
        }
        // The cost bound includes only explicit history. Provider-side references can hide billable input.
        if (request.hasNonNull("previous_response_id") || request.hasNonNull("conversation") || request.hasNonNull("prompt")) {
            throw new AtlasLogosBenchmark.DispatchBlockedException("benchmark requires explicit conversation history");
        }
        for (JsonNode item : request.path("input")) {
            if ("item_reference".equals(item.path("type").asText())) {
                throw new AtlasLogosBenchmark.DispatchBlockedException("benchmark does not price stored input references");
            }
        }
        String tier = text(request, "service_tier");
        if (tier != null && !tier.isBlank() && !"default".equalsIgnoreCase(tier) && !"auto".equalsIgnoreCase(tier)) {
            throw new AtlasLogosBenchmark.DispatchBlockedException("benchmark pricing does not cover service tier " + tier);
        }
        if (request != null && (request.has("prompt_cache_retention") || request.has("prompt_cache_retention_days"))) {
            throw new AtlasLogosBenchmark.DispatchBlockedException("benchmark pricing does not cover cache writes");
        }
    }

    private static boolean containsNonTextPart(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has("image_url") || node.has("input_audio") || node.has("audio_url") || node.has("image")) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsNonTextPart(child)) {
                    return true;
                }
            }
        }
        else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsNonTextPart(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            return mapper.readTree(body);
        }
        catch (IOException ex) {
            return null;
        }
    }

    private static byte[] bodyBytes(RequestBody body) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readByteArray();
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root == null ? null : root.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 409 || status == 425 || status == 429 || status >= 500;
    }
}

package de.tum.cit.aet.artemis.core.benchmark;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Profile-gated beans for the passive benchmark observer. */
@Configuration
@Profile(AtlasLogosBenchmark.PROFILE)
@EnableAspectJAutoProxy
public class AtlasLogosBenchmarkConfiguration {

    /** Creates file-backed telemetry from active-run.json and pricing.json. */
    @Bean
    AtlasLogosBenchmark.Telemetry atlasLogosBenchmarkTelemetry(ObjectMapper objectMapper) {
        return AtlasLogosBenchmark.Telemetry.fromEnvironment(objectMapper);
    }

    /** Adds the interceptor to Spring AI's existing official SDK transport. */
    @Bean
    AtlasLogosBenchmarkInterceptor atlasLogosBenchmarkInterceptor(AtlasLogosBenchmark.Telemetry telemetry, ObjectMapper objectMapper) {
        return new AtlasLogosBenchmarkInterceptor(telemetry, objectMapper);
    }

    /** Leaves SDK retries and model/tool behavior unchanged. */
    @Bean
    OpenAiHttpClientBuilderCustomizer atlasLogosBenchmarkHttpCustomizer(AtlasLogosBenchmarkInterceptor interceptor) {
        return builder -> builder.interceptor(interceptor);
    }

}

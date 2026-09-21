package com.pikume.back.creative.adapter.out.ai.gemini;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.creative.application.dto.DiaryIllustrationRequest;
import com.pikume.back.creative.application.exception.CreativeException;
import com.pikume.back.global.logging.CapturingLogAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiDiaryIllustrationAdapterTest {
    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void httpFailureIsLoggedOnceWithOriginalContextAndRestoresWorker() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var restoredContext = new CompletableFuture<String>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.create(sink ->
                executor.execute(() -> {
                    MDC.put("requestId", "22222222222222222222222222222222");
                    sink.success(ClientResponse.create(HttpStatus.BAD_GATEWAY).body("private provider body").build());
                    restoredContext.complete(MDC.get("requestId"));
                })));
        var adapter = adapter(builder);
        MDC.put("requestId", "11111111111111111111111111111111");

        try (var logs = new CapturingLogAppender(GeminiDiaryIllustrationAdapter.class)) {
            assertThatThrownBy(() -> adapter.generate(new DiaryIllustrationRequest("private prompt", "private image")))
                    .isInstanceOf(CreativeException.class);

            assertThat(restoredContext.get(5, TimeUnit.SECONDS)).isEqualTo("22222222222222222222222222222222");
            var errors = logs.events().stream().filter(event -> event.level() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).context()).containsEntry("requestId", "11111111111111111111111111111111");
            assertThat(errors.get(0).message()).contains("event=diary_illustration_generation", "status=502")
                    .doesNotContain("private", "test-key");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulProviderResponseKeepsTheGeneratedPayload() {
        var adapter = adapter(WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"data\":\"generated-image\"}}]}}]}")
                        .build())));

        var payload = adapter.generate(new DiaryIllustrationRequest("prompt", "reference"));

        assertThat(payload.imageBase64()).isEqualTo("generated-image");
        assertThat(payload.fileExtension()).isEqualTo("png");
    }

    private GeminiDiaryIllustrationAdapter adapter(WebClient.Builder builder) {
        var adapter = new GeminiDiaryIllustrationAdapter(builder, new ObjectMapper());
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://example.test");
        ReflectionTestUtils.setField(adapter, "apiKey", "test-key");
        ReflectionTestUtils.setField(adapter, "imageGenerationModel", "test-model");
        ReflectionTestUtils.setField(adapter, "timeout", Duration.ofSeconds(2));
        return adapter;
    }
}

package com.pikume.back.creative.adapter.out.ai.gemini;

import com.pikume.back.global.logging.RequestIdContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.pikume.back.creative.application.dto.DiaryIllustrationRequest;
import com.pikume.back.creative.application.dto.GeneratedIllustrationPayload;
import com.pikume.back.creative.application.port.out.GenerateDiaryIllustrationPort;
import com.pikume.back.creative.application.exception.CreativeErrorCode;
import com.pikume.back.creative.application.exception.CreativeException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiDiaryIllustrationAdapter implements GenerateDiaryIllustrationPort {

	private final WebClient.Builder webClientBuilder;
	private final ObjectMapper objectMapper;

	@Value("${gemini.api.key}")
	private String apiKey;

	@Value("${gemini.api.base-url}")
	private String baseUrl;

	@Value("${gemini.api.models.image-generation}")
	private String imageGenerationModel;

	@Value("${gemini.api.timeout:30s}")
	private Duration timeout;

	@Override
	public GeneratedIllustrationPayload generate(DiaryIllustrationRequest request) {
		log.debug("event=diary_illustration_generation outcome=started");
		RequestIdContext requestContext = RequestIdContext.capture();

		try {
			String response = webClientBuilder
					.baseUrl(baseUrl)
					.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.build()
					.post()
					.uri(String.format("/models/%s:generateContent?key=%s", imageGenerationModel, apiKey))
					.bodyValue(createImageEditRequest(request.referenceImageBase64(), request.prompt()))
					.retrieve()
					.bodyToMono(String.class)
					.timeout(timeout)
					.doOnError(WebClientResponseException.class, requestContext.wrap(error -> log.error(
							"event=diary_illustration_generation outcome=failed reason=provider_http_error status={} exception={}",
							error.getStatusCode().value(), error.getClass().getSimpleName())))
					.block();

			if (response == null || response.isBlank()) {
				throw new CreativeException(CreativeErrorCode.IMAGE_GENERATION_FAILED);
			}

			String imageBase64 = extractImageFromResponse(response);
			if (imageBase64.isBlank()) {
				throw new CreativeException(CreativeErrorCode.IMAGE_GENERATION_FAILED);
			}

			return new GeneratedIllustrationPayload(imageBase64, "png");
		} catch (CreativeException e) {
			throw e;
		} catch (WebClientResponseException e) {
			throw new CreativeException(CreativeErrorCode.IMAGE_GENERATION_FAILED, e);
		} catch (Exception e) {
			log.error("event=diary_illustration_generation outcome=failed exception={}", e.getClass().getSimpleName());
			throw new CreativeException(CreativeErrorCode.IMAGE_GENERATION_FAILED, e);
		}
	}

	private Map<String, Object> createImageEditRequest(String imageBase64, String prompt) {
		Map<String, Object> request = new HashMap<>();

		Map<String, Object> textPart = new HashMap<>();
		textPart.put("text", prompt);

		Map<String, Object> imagePart = new HashMap<>();
		Map<String, Object> inlineData = new HashMap<>();
		inlineData.put("mime_type", "image/png");
		inlineData.put("data", imageBase64);
		imagePart.put("inlineData", inlineData);

		Map<String, Object> content = new HashMap<>();
		content.put("parts", List.of(textPart, imagePart));
		request.put("contents", List.of(content));

		Map<String, Object> generationConfig = new HashMap<>();
		generationConfig.put("responseModalities", List.of("TEXT", "IMAGE"));
		request.put("generationConfig", generationConfig);

		return request;
	}

	private String extractImageFromResponse(String response) {
		try {
			JsonNode jsonNode = objectMapper.readTree(response);
			JsonNode candidates = jsonNode.get("candidates");

			if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
				JsonNode content = candidates.get(0).get("content");
				if (content != null) {
					JsonNode parts = content.get("parts");
					if (parts != null && parts.isArray()) {
						for (JsonNode part : parts) {
							JsonNode inlineData = part.get("inlineData");
							if (inlineData == null) {
								inlineData = part.get("inline_data");
							}

							if (inlineData != null && inlineData.get("data") != null) {
								return inlineData.get("data").asText();
							}
						}
					}
				}
			}

			throw new CreativeException(CreativeErrorCode.IMAGE_GENERATION_FAILED);
		} catch (CreativeException e) {
			throw e;
		} catch (Exception e) {
			log.error("event=diary_illustration_generation outcome=failed reason=invalid_response exception={}", e.getClass().getSimpleName());
			throw new CreativeException(CreativeErrorCode.IMAGE_GENERATION_FAILED, e);
		}
	}
}

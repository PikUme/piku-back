package com.pikume.back.global.adapter.out.email;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.pikume.back.global.email.EmailAddress;
import com.pikume.back.global.email.EmailAttachment;
import com.pikume.back.global.email.OutboundEmailException;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;
import java.time.Duration;
import java.util.regex.Pattern;

@Slf4j
public class ResendEmailAdapter implements SendOutboundEmailPort {
	private static final String PROVIDER = "resend";
	private static final Pattern SAFE_MESSAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,120}");
	private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(10);

	private final WebClient webClient;
	private final Duration requestDeadline;

	public ResendEmailAdapter(WebClient webClient) {
		this(webClient, REQUEST_DEADLINE);
	}

	public ResendEmailAdapter(WebClient webClient, Duration requestDeadline) {
		this.webClient = webClient;
		this.requestDeadline = requestDeadline;
	}

	@Override
	public void send(OutboundEmailMessage message) {
		try {
			ResendResponse response = webClient.post()
					.bodyValue(toRequest(message))
					.exchangeToMono(clientResponse -> {
						HttpStatusCode status = clientResponse.statusCode();
						if (!status.is2xxSuccessful()) {
							return clientResponse.releaseBody()
									.then(Mono.error(providerFailure(status.value())));
						}
						return clientResponse.bodyToMono(ResendResponse.class);
					})
					.block(requestDeadline);

			if (response == null || !isSafeMessageId(response.id())) {
				throw new OutboundEmailException(PROVIDER, "invalid_response", null);
			}
			log.info("event=email_sent provider={} messageId={}", PROVIDER, response.id());
		} catch (OutboundEmailException exception) {
			logFailure(exception);
			throw exception;
		} catch (RuntimeException exception) {
			OutboundEmailException failure = new OutboundEmailException(PROVIDER, classify(exception), null);
			logFailure(failure);
			throw failure;
		}
	}

	private ResendRequest toRequest(OutboundEmailMessage message) {
		return new ResendRequest(
				formatAddress(message.from()),
				List.of(message.to().address()),
				message.subject(),
				message.html(),
				message.attachments().stream().map(this::toResendAttachment).toList());
	}

	private String formatAddress(EmailAddress address) {
		return address.displayName().isBlank()
				? address.address()
				: address.displayName() + " <" + address.address() + ">";
	}

	private ResendAttachment toResendAttachment(EmailAttachment attachment) {
		return new ResendAttachment(
				attachment.filename(),
				Base64.getEncoder().encodeToString(attachment.content()),
				attachment.contentType());
	}

	private OutboundEmailException providerFailure(int statusCode) {
		String category = statusCode >= 500 ? "provider_unavailable"
				: statusCode >= 400 ? "provider_rejected" : "unexpected_status";
		return new OutboundEmailException(PROVIDER, category, statusCode);
	}

	private String classify(RuntimeException exception) {
		Throwable cause = Exceptions.unwrap(exception);
		while (cause != null) {
			if (cause.getClass().getSimpleName().contains("Timeout")) {
				return "timeout";
			}
			cause = cause.getCause();
		}
		return "transport_error";
	}

	private boolean isSafeMessageId(String messageId) {
		return messageId != null && SAFE_MESSAGE_ID.matcher(messageId).matches();
	}

	private void logFailure(OutboundEmailException exception) {
		log.warn("event=email_send_failed provider={} category={} status={}",
				PROVIDER, exception.category(), exception.statusCode());
	}

	private record ResendRequest(
			@JsonProperty("from") String from,
			@JsonProperty("to") List<String> to,
			@JsonProperty("subject") String subject,
			@JsonProperty("html") String html,
			@JsonProperty("attachments") List<ResendAttachment> attachments) {
	}

	private record ResendAttachment(
			@JsonProperty("filename") String filename,
			@JsonProperty("content") String content,
			@JsonProperty("content_type") @JsonInclude(JsonInclude.Include.NON_NULL) String contentType) {
	}

	private record ResendResponse(@JsonProperty("id") String id) {
	}
}

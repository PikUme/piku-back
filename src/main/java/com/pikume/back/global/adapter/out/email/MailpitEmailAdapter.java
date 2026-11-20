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
public class MailpitEmailAdapter implements SendOutboundEmailPort {
	private static final String PROVIDER = "mailpit";
	private static final Pattern SAFE_MESSAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,120}");
	private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(10);

	private final WebClient webClient;
	private final Duration requestDeadline;

	public MailpitEmailAdapter(WebClient webClient) {
		this(webClient, REQUEST_DEADLINE);
	}

	public MailpitEmailAdapter(WebClient webClient, Duration requestDeadline) {
		this.webClient = webClient;
		this.requestDeadline = requestDeadline;
	}

	@Override
	public void send(OutboundEmailMessage message) {
		try {
			MailpitResponse response = webClient.post()
					.bodyValue(toRequest(message))
					.exchangeToMono(clientResponse -> {
						HttpStatusCode status = clientResponse.statusCode();
						if (!status.is2xxSuccessful()) {
							return clientResponse.releaseBody()
									.then(Mono.error(providerFailure(status.value())));
						}
						return clientResponse.bodyToMono(MailpitResponse.class);
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

	private MailpitRequest toRequest(OutboundEmailMessage message) {
		return new MailpitRequest(
				toMailpitAddress(message.from()),
				List.of(toMailpitAddress(message.to())),
				message.subject(),
				message.html(),
				message.attachments().stream().map(this::toMailpitAttachment).toList());
	}

	private MailpitAddress toMailpitAddress(EmailAddress address) {
		return new MailpitAddress(address.address(), address.displayName());
	}

	private MailpitAttachment toMailpitAttachment(EmailAttachment attachment) {
		return new MailpitAttachment(
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

	private record MailpitRequest(
			@JsonProperty("From") MailpitAddress from,
			@JsonProperty("To") List<MailpitAddress> to,
			@JsonProperty("Subject") String subject,
			@JsonProperty("HTML") String html,
			@JsonProperty("Attachments") List<MailpitAttachment> attachments) {
	}

	private record MailpitAddress(
			@JsonProperty("Email") String email,
			@JsonProperty("Name") String name) {
	}

	private record MailpitAttachment(
			@JsonProperty("Filename") String filename,
			@JsonProperty("Content") String content,
			@JsonProperty("ContentType") @JsonInclude(JsonInclude.Include.NON_NULL) String contentType) {
	}

	private record MailpitResponse(@JsonProperty("ID") String id) {
	}
}

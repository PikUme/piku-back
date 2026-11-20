package com.pikume.back.global.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.global.adapter.out.email.MailpitEmailAdapter;
import com.pikume.back.global.adapter.out.email.ResendEmailAdapter;
import com.pikume.back.global.email.EmailAddress;
import com.pikume.back.global.email.EmailAttachment;
import com.pikume.back.global.email.OutboundEmailException;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HTTP 메일 provider 계약")
class OutboundEmailProviderContractTest {
	private final List<HttpServer> servers = new ArrayList<>();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterEach
	void stopServers() {
		servers.forEach(server -> server.stop(0));
	}

	@Test
	@DisplayName("Mailpit에 주소 객체, HTML과 원본 첨부 바이트를 JSON으로 전송한다")
	void sendsMailpitRequest() throws Exception {
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = server("/api/v1/send", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"ID\":\"mailpit-message-1\"}");
		});
		MailpitEmailAdapter adapter = new MailpitEmailAdapter(client(endpoint(server, "/api/v1/send"), null));

		adapter.send(message());

		JsonNode request = objectMapper.readTree(requestBody.get());
		assertThat(request.path("From").path("Email").asText()).isEqualTo("sender@pikume.test");
		assertThat(request.path("From").path("Name").asText()).isEqualTo("PikUme");
		assertThat(request.path("To").get(0).path("Email").asText()).isEqualTo("recipient@pikume.test");
		assertThat(request.path("Subject").asText()).isEqualTo("Test subject");
		assertThat(request.path("HTML").asText()).isEqualTo("<p>Test HTML</p>");
		JsonNode attachment = request.path("Attachments").get(0);
		assertThat(attachment.path("Filename").asText()).isEqualTo("original-name.png");
		assertThat(attachment.path("ContentType").asText()).isEqualTo("image/png");
		assertThat(Base64.getDecoder().decode(attachment.path("Content").asText()))
				.isEqualTo(new byte[] {0, 1, 2, 3});
	}

	@Test
	@DisplayName("Resend에 bearer 인증과 공식 JSON 형태를 사용한다")
	void sendsResendRequest() throws Exception {
		AtomicReference<String> requestBody = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer server = server("/emails", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			respond(exchange, 200, "{\"id\":\"resend-message-1\"}");
		});
		ResendEmailAdapter adapter = new ResendEmailAdapter(client(endpoint(server, "/emails"), "loopback-test-key"));

		adapter.send(message());

		JsonNode request = objectMapper.readTree(requestBody.get());
		assertThat(authorization.get()).isEqualTo("Bearer loopback-test-key");
		assertThat(request.path("from").asText()).isEqualTo("PikUme <sender@pikume.test>");
		assertThat(request.path("to").get(0).asText()).isEqualTo("recipient@pikume.test");
		assertThat(request.path("subject").asText()).isEqualTo("Test subject");
		assertThat(request.path("html").asText()).isEqualTo("<p>Test HTML</p>");
		JsonNode attachment = request.path("attachments").get(0);
		assertThat(attachment.path("filename").asText()).isEqualTo("original-name.png");
		assertThat(attachment.path("content_type").asText()).isEqualTo("image/png");
		assertThat(Base64.getDecoder().decode(attachment.path("content").asText()))
				.isEqualTo(new byte[] {0, 1, 2, 3});
	}

	@Test
	@DisplayName("Resend 오류 분류에는 응답 본문이나 키가 포함되지 않는다")
	void responseFailureDoesNotExposeProviderBody() {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = server("/emails", exchange -> {
			requests.incrementAndGet();
			respond(exchange, 429, "{\"message\":\"private recipient and key data\"}");
		});
		ResendEmailAdapter adapter = new ResendEmailAdapter(client(endpoint(server, "/emails"), "loopback-test-key"));

		assertThatThrownBy(() -> adapter.send(message()))
				.isInstanceOfSatisfying(OutboundEmailException.class, exception -> {
					assertThat(exception.provider()).isEqualTo("resend");
					assertThat(exception.category()).isEqualTo("provider_rejected");
					assertThat(exception.statusCode()).isEqualTo(429);
					assertThat(exception.getMessage()).doesNotContain("private", "recipient", "key");
				});
		assertThat(requests).hasValue(1);
	}

	@Test
	@DisplayName("리디렉션을 따라 Authorization 키를 전달하지 않는다")
	void doesNotFollowRedirects() {
		AtomicInteger redirectedRequests = new AtomicInteger();
		HttpServer server = server("/emails", exchange -> {
			exchange.getResponseHeaders().add("Location", "/capture");
			respond(exchange, 302, "");
		});
		server.createContext("/capture", exchange -> {
			redirectedRequests.incrementAndGet();
			respond(exchange, 200, "{\"id\":\"unexpected\"}");
		});
		ResendEmailAdapter adapter = new ResendEmailAdapter(client(endpoint(server, "/emails"), "loopback-test-key"));

		assertThatThrownBy(() -> adapter.send(message()))
				.isInstanceOfSatisfying(OutboundEmailException.class, exception -> {
					assertThat(exception.category()).isEqualTo("unexpected_status");
					assertThat(exception.statusCode()).isEqualTo(302);
				});
		assertThat(redirectedRequests).hasValue(0);
	}

	@Test
	@DisplayName("전체 요청 기한은 trickling response body도 제한한다")
	void totalDeadlineIncludesResponseBodyDrain() {
		HttpServer server = server("/api/v1/send", this::sendTricklingResponse);
		MailpitEmailAdapter adapter = new MailpitEmailAdapter(
				client(endpoint(server, "/api/v1/send"), null), Duration.ofMillis(200));
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> adapter.send(message()))
				.isInstanceOfSatisfying(OutboundEmailException.class, exception -> {
					assertThat(exception.category()).isEqualTo("timeout");
					assertThat(exception.statusCode()).isNull();
				});
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
	}

	private void sendTricklingResponse(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write("{\"ID\":\"message-1\"}".getBytes(StandardCharsets.UTF_8));
			output.flush();
			for (int index = 0; index < 30; index++) {
				output.write(' ');
				output.flush();
				try {
					Thread.sleep(40);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext(path, handler);
			server.start();
			servers.add(server);
			return server;
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private WebClient client(String endpoint, String bearerToken) {
		return OutboundEmailClientFactory.create(endpoint, bearerToken);
	}

	private String endpoint(HttpServer server, String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] content = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, content.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private OutboundEmailMessage message() {
		return new OutboundEmailMessage(
				new EmailAddress("sender@pikume.test", "PikUme"),
				new EmailAddress("recipient@pikume.test", ""),
				"Test subject",
				"<p>Test HTML</p>",
				List.of(new EmailAttachment("original-name.png", "image/png", new byte[] {0, 1, 2, 3})));
	}
}

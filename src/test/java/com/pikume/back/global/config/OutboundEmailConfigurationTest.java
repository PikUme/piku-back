package com.pikume.back.global.config;

import com.pikume.back.global.adapter.out.email.MailpitEmailAdapter;
import com.pikume.back.global.adapter.out.email.ResendEmailAdapter;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("프로필별 HTTP 메일 설정")
class OutboundEmailConfigurationTest {

	@Test
	@DisplayName("dev는 Mailpit HTTP provider를 선택한다")
	void developmentUsesMailpit() {
		contextRunner("dev").run(context -> {
			assertThat(context).hasSingleBean(SendOutboundEmailPort.class);
			assertThat(context).hasSingleBean(MailpitEmailAdapter.class);
			assertThat(context).doesNotHaveBean(ResendEmailAdapter.class);
			assertThat(context.getEnvironment().getProperty("outbound-email.mailpit-url"))
					.isEqualTo("http://127.0.0.1:8025/api/v1/send");
		});
	}

	@Test
	@DisplayName("알 수 있는 비운영 프로필은 네트워크 호출 없이 Mailpit client를 구성한다")
	void unknownNonProductionProfileUsesMailpit() {
		contextRunner("test").run(context -> {
			assertThat(context).hasSingleBean(MailpitEmailAdapter.class);
			assertThat(context.getStartupFailure()).isNull();
		});
	}

	@Test
	@DisplayName("운영 프로필이 함께 활성화되면 Resend를 선택한다")
	void productionProfileTakesPrecedence() {
		productionRunner("dev", "prod").run(context -> {
			assertThat(context.getEnvironment().getProperty("outbound-email.api-key")).isEqualTo("test-resend-key");
			assertThat(context).hasSingleBean(SendOutboundEmailPort.class);
			assertThat(context).hasSingleBean(ResendEmailAdapter.class);
			assertThat(context).doesNotHaveBean(MailpitEmailAdapter.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"outbound-email.api-key", "MAIL_FROM_ADDRESS", "FEEDBACK_RECIPIENT"})
	@DisplayName("필수 운영 메일 설정이 비어 있으면 시작하지 않는다")
	void productionRejectsBlankSettings(String setting) {
		ApplicationContextRunner runner = setting.equals("outbound-email.api-key")
				? productionRunnerWithoutApiKey("prod")
				: productionRunner("prod");
		runner.withPropertyValues(setting + "=").run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.hasRootCauseMessage(setting + " 운영 메일 설정은 필수입니다."));
	}

	private ApplicationContextRunner productionRunner(String... profiles) {
		return contextRunner(profiles)
				.withInitializer(context -> addResendSystemEnvironmentVariable(context.getEnvironment()))
				.withPropertyValues(
						"MAIL_FROM_ADDRESS=sender@pikume.test",
						"FEEDBACK_RECIPIENT=feedback@pikume.test");
	}

	private ApplicationContextRunner productionRunnerWithoutApiKey(String... profiles) {
		return contextRunner(profiles).withPropertyValues(
				"MAIL_FROM_ADDRESS=sender@pikume.test",
				"FEEDBACK_RECIPIENT=feedback@pikume.test");
	}

	private void addResendSystemEnvironmentVariable(ConfigurableEnvironment environment) {
		environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
				"systemEnvironment", Map.of("OUTBOUNDEMAIL_APIKEY", "test-resend-key")));
		ConfigurationPropertySources.attach(environment);
	}

	private ApplicationContextRunner contextRunner(String... profiles) {
		return new ApplicationContextRunner()
				.withUserConfiguration(OutboundEmailConfiguration.class, ProductionMailStartupValidator.class)
				.withInitializer(context -> {
					ConfigurableEnvironment environment = context.getEnvironment();
					environment.setActiveProfiles(profiles);
					loadMainResource(environment, "application");
					Arrays.stream(profiles)
							.filter(profile -> !profile.equals("test"))
							.forEach(profile -> loadMainResource(environment, "application-" + profile));
				});
	}

	private void loadMainResource(ConfigurableEnvironment environment, String name) {
		try {
			new YamlPropertySourceLoader().load(name, new FileSystemResource("src/main/resources/" + name + ".yml"))
					.forEach(environment.getPropertySources()::addFirst);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}
}

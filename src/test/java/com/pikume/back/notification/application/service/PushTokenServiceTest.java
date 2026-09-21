package com.pikume.back.notification.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pikume.back.notification.application.port.out.RegisterPushTokenPort;
import com.pikume.back.notification.application.port.out.RevokePushTokenPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushTokenService")
class PushTokenServiceTest {

	@InjectMocks
	private PushTokenService service;

	@Mock
	private RegisterPushTokenPort registerPushTokenPort;
	@Mock
	private RevokePushTokenPort revokePushTokenPort;

	@Test
	@DisplayName("Push Token 등록을 목적별 Port에 위임한다")
	void registersPushToken() {
		service.registerPushToken("user-id", "token-123", "device-1");

		then(registerPushTokenPort).should().registerPushToken("user-id", "token-123", "device-1");
	}

	@Test
	@DisplayName("기기별 Push Token 해제를 목적별 Port에 위임한다")
	void revokesPushTokenForDevice() {
		service.revokePushTokenForDevice("user-id", "device-1");

		then(revokePushTokenPort).should().revokePushTokenForDevice("user-id", "device-1");
	}

	@Test
	@DisplayName("Push Token 등록·해제 로그에 raw deviceId를 남기지 않는다")
	void doesNotLogRawDeviceId() {
		Logger logger = (Logger) LoggerFactory.getLogger(PushTokenService.class);
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			service.registerPushToken("user-id", "token-123", "sensitive-device-id");
			service.revokePushTokenForDevice("user-id", "sensitive-device-id");
		} finally {
			detachLogAppender(appender);
			logger.setLevel(previousLevel);
		}

		assertThat(appender.list).isNotEmpty();
		assertThat(appender.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.noneMatch(message -> message.contains("sensitive-device-id"));
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(PushTokenService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(PushTokenService.class);
		logger.detachAppender(appender);
	}
}

package com.pikume.back.notification.adapter.out.push;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LocalPushAdapter")
class LocalPushAdapterTest {

	private final LocalPushAdapter localPushAdapter = new LocalPushAdapter();

	@Test
	@DisplayName("비운영 Push 로그는 DEBUG로 남기며 토큰과 알림 본문을 노출하지 않는다")
	void logsLocalPushDelivery() {
		Logger logger = (Logger) LoggerFactory.getLogger(LocalPushAdapter.class);
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			localPushAdapter.deliverPushNotification("target-token", "알림 본문");
		} finally {
			detachLogAppender(appender);
			logger.setLevel(previousLevel);
		}

		assertThat(appender.list).singleElement().satisfies(event ->
				assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
		assertThat(formattedMessages(appender))
				.noneMatch(message -> message.contains("알림 본문") || message.contains("target-token"));
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(LocalPushAdapter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(LocalPushAdapter.class);
		logger.detachAppender(appender);
	}

	private java.util.List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}
}

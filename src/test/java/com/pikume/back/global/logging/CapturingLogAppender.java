package com.pikume.back.global.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CapturingLogAppender extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final List<Event> events = new CopyOnWriteArrayList<>();

    public CapturingLogAppender(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        start();
        logger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(new Event(event.getLevel(), event.getFormattedMessage(), Map.copyOf(event.getMDCPropertyMap())));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        logger.setLevel(previousLevel);
        stop();
    }

    public record Event(Level level, String message, Map<String, String> context) {
    }
}

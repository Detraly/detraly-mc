package com.detraly.analytics.pulse;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

public final class ConsoleCapture {
    private static final String APPENDER_NAME = "DetralyPulseConsole";
    private static final int MAX_STACK_LINES = 20;

    private final ConsoleBuffer buffer;
    private final BooleanSupplier wanted;
    private Appender appender;

    public ConsoleCapture(ConsoleBuffer buffer, BooleanSupplier wanted) {
        this.buffer = buffer;
        this.wanted = wanted;
    }

    public synchronized boolean start() {
        if (appender != null) {
            return true;
        }
        try {
            Logger root = (Logger) LogManager.getRootLogger();
            Appender created = new Appender();
            created.start();
            root.addAppender(created);
            appender = created;
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public synchronized void stop() {
        if (appender == null) {
            return;
        }
        try {
            ((Logger) LogManager.getRootLogger()).removeAppender(appender);
            appender.stop();
        } catch (RuntimeException | LinkageError ignored) {

        }
        appender = null;
    }

    private final class Appender extends AbstractAppender {
        Appender() {
            super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            try {
                if (event == null || event.getMessage() == null || !wanted.getAsBoolean()) {
                    return;
                }

                String thread = event.getThreadName();
                if (thread != null && thread.startsWith("Async Chat Thread")) {
                    return;
                }
                long seconds = event.getTimeMillis() / 1000L;
                String prefix = "[" + event.getLevel().name() + "] ";
                add(prefix, event.getMessage().getFormattedMessage(), seconds);
                Throwable thrown = event.getThrown();
                if (thrown != null) {
                    add(prefix, String.valueOf(thrown), seconds);
                    StackTraceElement[] stack = thrown.getStackTrace();
                    for (int i = 0; i < stack.length && i < MAX_STACK_LINES; i++) {
                        add(prefix, "\tat " + stack[i], seconds);
                    }
                }
            } catch (Throwable ignored) {

            }
        }

        private void add(String prefix, String message, long seconds) {
            List<String> lines = ConsoleRedactor.clean(message);
            for (String line : lines) {
                buffer.add(new ConsoleBuffer.Line(prefix + line, seconds));
            }
        }
    }
}

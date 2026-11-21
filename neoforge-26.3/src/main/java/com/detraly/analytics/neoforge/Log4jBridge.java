package com.detraly.analytics.neoforge;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.logging.log4j.LogManager;

final class Log4jBridge extends Handler {
    private final org.apache.logging.log4j.Logger target;

    private Log4jBridge(org.apache.logging.log4j.Logger target) {
        this.target = target;
    }

    static Logger create(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        for (Handler existing : logger.getHandlers()) {
            logger.removeHandler(existing);
        }
        logger.addHandler(new Log4jBridge(LogManager.getLogger(name)));
        logger.setLevel(Level.INFO);
        return logger;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        String message = record.getMessage();
        int level = record.getLevel().intValue();
        if (level >= Level.SEVERE.intValue()) {
            target.error(message);
        } else if (level >= Level.WARNING.intValue()) {
            target.warn(message);
        } else {
            target.info(message);
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}

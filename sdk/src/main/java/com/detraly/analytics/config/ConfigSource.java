package com.detraly.analytics.config;

import java.util.Map;
import java.util.Properties;

public interface ConfigSource {
    String getString(String key, String fallback);

    boolean getBoolean(String key, boolean fallback);

    static ConfigSource of(Map<String, ?> values) {
        return new ConfigSource() {
            @Override
            public String getString(String key, String fallback) {
                Object v = values.get(key);
                return v == null ? fallback : v.toString();
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                Object v = values.get(key);
                if (v instanceof Boolean b) {
                    return b;
                }
                if (v != null) {
                    String s = v.toString().trim();
                    if (s.equalsIgnoreCase("true")) {
                        return true;
                    }
                    if (s.equalsIgnoreCase("false")) {
                        return false;
                    }
                }
                return fallback;
            }
        };
    }

    static ConfigSource of(Properties properties) {
        Map<String, Object> copy = new java.util.HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            copy.put(name, properties.getProperty(name));
        }
        return of(copy);
    }
}

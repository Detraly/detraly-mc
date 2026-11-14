package com.detraly.analytics.pulse;

import org.json.JSONObject;

public interface Profiler {
    void attach();

    void detach();

    void startSampling();

    void stopSampling();

    JSONObject drainMessage();
}

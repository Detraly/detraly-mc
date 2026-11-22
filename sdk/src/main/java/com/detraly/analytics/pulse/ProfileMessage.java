package com.detraly.analytics.pulse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProfileMessage {
    static final int MAX_SOURCES = 32;
    static final int MAX_BYTES = 4096;

    public record Count(long samples, long spikes) {
    }

    private ProfileMessage() {
    }

    public static JSONObject build(long windowSeconds, long samples, long busy, Long spikeSamples,
            Map<ProfileAttribution.Source, Count> counts) {
        if (samples < 1L) {
            return null;
        }
        samples = Math.min(samples, 1_000_000L);
        busy = Math.max(0L, Math.min(busy, samples));
        Long spikeTop = spikeSamples == null ? null : Math.max(0L, Math.min(spikeSamples, busy));

        long otherTotal = 0L;
        long otherSpikes = 0L;
        List<Map.Entry<ProfileAttribution.Source, Count>> sorted = new ArrayList<>();
        for (Map.Entry<ProfileAttribution.Source, Count> e : counts.entrySet()) {
            if (e.getValue().samples() <= 0L) {
                continue;
            }
            if (e.getKey().kind().equals("other")) {
                otherTotal += e.getValue().samples();
                otherSpikes += e.getValue().spikes();
            } else {
                sorted.add(e);
            }
        }
        sorted.sort((a, b) -> {
            int c = Long.compare(b.getValue().samples(), a.getValue().samples());
            if (c != 0) {
                return c;
            }
            c = a.getKey().kind().compareTo(b.getKey().kind());
            return c != 0 ? c : a.getKey().name().compareTo(b.getKey().name());
        });

        int keep = sorted.size() > MAX_SOURCES ? MAX_SOURCES - 1 : sorted.size();
        while (true) {
            long folded = otherTotal;
            long foldedSpikes = otherSpikes;
            for (int i = keep; i < sorted.size(); i++) {
                folded += sorted.get(i).getValue().samples();
                foldedSpikes += sorted.get(i).getValue().spikes();
            }
            JSONObject json = assemble(windowSeconds, samples, busy, spikeTop, sorted, keep,
                    new Count(folded, foldedSpikes));
            if (keep == 0 || json.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
                return json;
            }
            keep--;
        }
    }

    private static JSONObject assemble(long windowSeconds, long samples, long busy, Long spikeTop,
            List<Map.Entry<ProfileAttribution.Source, Count>> sorted, int keep, Count other) {
        JSONArray sources = new JSONArray();
        long sum = 0L;
        long spikeLeft = spikeTop == null ? 0L : spikeTop;
        for (int i = 0; i < keep; i++) {
            ProfileAttribution.Source s = sorted.get(i).getKey();

            long n = Math.min(sorted.get(i).getValue().samples(), busy - sum);
            if (n <= 0L) {
                continue;
            }
            sum += n;
            long spikes = Math.min(Math.min(sorted.get(i).getValue().spikes(), n), spikeLeft);
            spikeLeft -= spikes;
            sources.put(source(s.kind(), s.name(), n, spikeTop == null ? null : spikes));
        }
        long otherSamples = Math.min(other.samples(), busy - sum);
        if (otherSamples > 0L) {
            long spikes = Math.min(Math.min(other.spikes(), otherSamples), spikeLeft);
            sources.put(source("other", "other", otherSamples, spikeTop == null ? null : spikes));
        }
        JSONObject json = new JSONObject();
        json.put("type", "profile");
        json.put("window_seconds", Math.max(10L, Math.min(600L, windowSeconds)));
        json.put("samples", samples);
        json.put("busy_samples", busy);
        if (spikeTop != null) {
            json.put("spike_samples", spikeTop);
        }
        json.put("sources", sources);
        return json;
    }

    private static JSONObject source(String kind, String name, long samples, Long spikes) {
        JSONObject o = new JSONObject();
        o.put("kind", kind);
        o.put("name", name);
        o.put("samples", samples);
        if (spikes != null) {
            o.put("spike_samples", spikes);
        }
        return o;
    }
}

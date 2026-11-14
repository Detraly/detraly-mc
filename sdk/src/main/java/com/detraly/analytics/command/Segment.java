package com.detraly.analytics.command;

public record Segment(Tone tone, String text) {
    public static Segment of(Tone tone, String text) {
        return new Segment(tone, text);
    }

    public static Segment[] prefixed(Tone tone, String text) {
        return new Segment[] {new Segment(Tone.BRAND, "[Detraly] "), new Segment(tone, text)};
    }
}

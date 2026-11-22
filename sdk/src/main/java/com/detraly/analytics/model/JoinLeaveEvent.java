package com.detraly.analytics.model;

import java.util.UUID;

public record JoinLeaveEvent(Type type, String username, UUID uuid, long timestampSeconds, String ip) {

    public enum Type {
        JOIN("join"),
        LEAVE("leave");

        private final String wire;

        Type(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public JoinLeaveEvent withoutIp() {
        return new JoinLeaveEvent(type, username, uuid, timestampSeconds, null);
    }

    @Override
    public String toString() {
        return "JoinLeaveEvent[" + type.wire() + ", " + username + ", ip=" + (ip == null ? "none" : "omitted") + "]";
    }
}

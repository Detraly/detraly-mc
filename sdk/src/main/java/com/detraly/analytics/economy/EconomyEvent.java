package com.detraly.analytics.economy;

import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;

/** One entry of an {@code economy} Pulse message. The text fields are checked before an event is made. */
public sealed interface EconomyEvent {
    String REASON_JOIN = "join";
    String REASON_QUIT = "quit";
    String REASON_POLL = "poll";
    String REASON_SWEEP = "sweep";
    String REASON_AFTER = "after";

    String eid();

    long ts();

    JSONObject toJson();

    /** Sweep samples are the first to be dropped when the buffer is full. */
    default boolean isSweep() {
        return false;
    }

    /** 32 lowercase hex characters, random for each event. */
    static String newEid() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }

    record Balance(String eid, long ts, String uuid, String name, double balance, String reason)
            implements EconomyEvent {
        @Override
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("eid", eid);
            o.put("ts", ts);
            o.put("kind", "balance");
            o.put("uuid", uuid);
            o.put("name", name);
            o.put("balance", balance);
            o.put("reason", reason);
            return o;
        }

        @Override
        public boolean isSweep() {
            return REASON_SWEEP.equals(reason);
        }
    }

    record Transfer(String eid, long ts, String fromUuid, String fromName, String toUuid, String toName,
            double amount, String command) implements EconomyEvent {
        @Override
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("eid", eid);
            o.put("ts", ts);
            o.put("kind", "transfer");
            o.put("from_uuid", fromUuid);
            o.put("from_name", fromName);
            o.put("to_uuid", toUuid);
            o.put("to_name", toName);
            o.put("amount", amount);
            o.put("command", command);
            return o;
        }
    }

    /** {@code actorUuid} and {@code actorName} are both null for the console. */
    record Admin(String eid, long ts, String action, String targetUuid, String targetName, double amount,
            String command, String actorUuid, String actorName) implements EconomyEvent {
        @Override
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("eid", eid);
            o.put("ts", ts);
            o.put("kind", "admin");
            o.put("action", action);
            o.put("target_uuid", targetUuid);
            o.put("target_name", targetName);
            o.put("amount", amount);
            o.put("command", command);
            if (actorUuid != null && actorName != null) {
                o.put("actor_uuid", actorUuid);
                o.put("actor_name", actorName);
            }
            return o;
        }
    }
}

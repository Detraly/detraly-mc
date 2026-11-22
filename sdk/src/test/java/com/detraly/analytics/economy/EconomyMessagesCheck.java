package com.detraly.analytics.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/** Checks the shape of the economy messages against the Pulse contract: fields, no extras, formats. */
public final class EconomyMessagesCheck {
    private static int failures;

    private static final String STEVE = "0f8e7c52-1a2b-4c3d-8e9f-001122334455";
    private static final String ALEX = "1a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d";

    public static void main(String[] args) {
        long ts = 1_730_000_000L;

        JSONObject balance = new EconomyEvent.Balance(EconomyEvent.newEid(), ts, STEVE, "Steve", 1520.5, "poll")
                .toJson();
        check("balance fields", keys(balance).equals(Set.of("eid", "ts", "kind", "uuid", "name", "balance",
                "reason")));
        check("balance kind and values", balance.getString("kind").equals("balance")
                && balance.getDouble("balance") == 1520.5 && balance.getLong("ts") == ts);

        JSONObject transfer = new EconomyEvent.Transfer(EconomyEvent.newEid(), ts, STEVE, "Steve", ALEX, "Alex",
                250.0, "pay").toJson();
        check("transfer fields", keys(transfer).equals(Set.of("eid", "ts", "kind", "from_uuid", "from_name",
                "to_uuid", "to_name", "amount", "command")));
        check("transfer kind", transfer.getString("kind").equals("transfer")
                && transfer.getString("command").equals("pay"));

        JSONObject console = new EconomyEvent.Admin(EconomyEvent.newEid(), ts, "give", ALEX, "Alex", 1000.0, "eco",
                null, null).toJson();
        check("admin fields without an actor (console)", keys(console).equals(Set.of("eid", "ts", "kind", "action",
                "target_uuid", "target_name", "amount", "command")));
        JSONObject byPlayer = new EconomyEvent.Admin(EconomyEvent.newEid(), ts, "reset", ALEX, "Alex", 0.0, "eco",
                STEVE, "Steve").toJson();
        check("admin fields with an actor", keys(byPlayer).equals(Set.of("eid", "ts", "kind", "action",
                "target_uuid", "target_name", "amount", "command", "actor_uuid", "actor_name")));
        check("admin kind", byPlayer.getString("kind").equals("admin"));

        for (JSONObject event : List.of(balance, transfer, console, byPlayer)) {
            check("eid is 32 lowercase hex characters", event.getString("eid").matches("[0-9a-f]{32}"));
        }
        for (String uuid : List.of(balance.getString("uuid"), transfer.getString("from_uuid"),
                transfer.getString("to_uuid"), console.getString("target_uuid"), byPlayer.getString("actor_uuid"))) {
            check("uuid is canonical lowercase", uuid.length() == 36 && uuid.equals(uuid.toLowerCase())
                    && uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }
        java.util.UUID mixed = java.util.UUID.fromString("0F8E7C52-1A2B-4C3D-8E9F-AABBCCDDEEFF");
        check("a UUID object prints lowercase", mixed.toString().equals(mixed.toString().toLowerCase()));

        Set<String> eids = new TreeSet<>();
        for (int i = 0; i < 1000; i++) {
            eids.add(EconomyEvent.newEid());
        }
        check("eids do not repeat", eids.size() == 1000);

        JSONObject message = EconomyMessages.batch(42L, List.of(new EconomyEvent.Balance(EconomyEvent.newEid(), ts,
                STEVE, "Steve", 5.0, "join")));
        check("economy message fields", keys(message).equals(Set.of("type", "id", "events"))
                && message.getString("type").equals("economy") && message.getLong("id") == 42L
                && message.getJSONArray("events").length() == 1);

        List<EconomyEvent> biggest = new ArrayList<>();
        String longName = "A".repeat(32);
        for (int i = 0; i < EconomyMessages.MAX_EVENTS; i++) {
            biggest.add(new EconomyEvent.Admin(EconomyEvent.newEid(), ts, "reset", ALEX, longName, 999999999999999.0,
                    "a".repeat(32), STEVE, longName));
        }
        int bytes = EconomyMessages.batch(9007199254740991L, biggest).toString().getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length;
        check("the largest possible message is under 65536 bytes (" + bytes + ")", bytes < 65536);

        EconomyHost.Info info = new EconomyHost.Info("EssentialsX Economy", "Dollar", "Dollars", 2, false);
        JSONObject infoMessage = EconomyMessages.info(info);
        check("economy_info fields", keys(infoMessage).equals(Set.of("type", "provider", "currency_singular",
                "currency_plural", "fractional_digits", "bank_support"))
                && infoMessage.getString("type").equals("economy_info")
                && infoMessage.getString("provider").equals("EssentialsX Economy")
                && infoMessage.getInt("fractional_digits") == 2 && !infoMessage.getBoolean("bank_support"));
        check("economy_info is under 4096 bytes", infoMessage.toString().length() < 4096);

        JSONObject empty = EconomyMessages.info(new EconomyHost.Info("X", "", null, -1, true));
        check("empty currency names are allowed", empty.getString("currency_singular").isEmpty()
                && empty.getString("currency_plural").isEmpty() && empty.getInt("fractional_digits") == -1);
        check("fractional digits are clamped", EconomyMessages.info(new EconomyHost.Info("X", "", "", 99, false))
                .getInt("fractional_digits") == 8
                && EconomyMessages.info(new EconomyHost.Info("X", "", "", -5, false))
                        .getInt("fractional_digits") == -1);
        check("an empty provider gets a name", !EconomyMessages.info(new EconomyHost.Info("  ", "", "", 2, false))
                .getString("provider").isEmpty());

        check("text drops angle brackets", EconomyMessages.text("<b>Gold</b>", 64).equals("bGold/b"));
        check("text drops the section sign", EconomyMessages.text("§aGold", 64).equals("aGold"));
        check("text drops control and format characters",
                EconomyMessages.text("Go\u0000l​d\n", 64).equals("Gold"));
        check("text trims spaces", EconomyMessages.text("  Gold coins  ", 64).equals("Gold coins"));
        check("text keeps ordinary non-ASCII", EconomyMessages.text("Euro €", 64).equals("Euro €"));
        check("text is cut at the byte limit", EconomyMessages.text("a".repeat(200), 128).length() == 128
                && EconomyMessages.text("€".repeat(100), 64).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        check("text of null is empty", EconomyMessages.text(null, 10).isEmpty());

        check("valid names", EconomyMessages.validName("Steve_1") && EconomyMessages.validName(".Bedrock-Player")
                && EconomyMessages.validName("*Floodgate") && EconomyMessages.validName("a".repeat(32)));
        check("invalid names", !EconomyMessages.validName("") && !EconomyMessages.validName("a".repeat(33))
                && !EconomyMessages.validName("Ste ve") && !EconomyMessages.validName(null)
                && !EconomyMessages.validName("Stéve"));
        check("valid labels", EconomyMessages.validLabel("pay") && EconomyMessages.validLabel("essentials:pay"));
        check("invalid labels", !EconomyMessages.validLabel("Pay") && !EconomyMessages.validLabel("pay now")
                && !EconomyMessages.validLabel(""));
        check("balance limits", EconomyMessages.validBalance(-1e15) && EconomyMessages.validBalance(1e15)
                && !EconomyMessages.validBalance(1.0000001e15) && !EconomyMessages.validBalance(Double.NaN)
                && !EconomyMessages.validBalance(Double.POSITIVE_INFINITY));

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> out = new TreeSet<>();
        for (String k : o.keySet()) {
            out.add(k);
        }
        return out;
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }
}

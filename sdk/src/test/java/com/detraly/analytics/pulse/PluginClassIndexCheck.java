package com.detraly.analytics.pulse;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public final class PluginClassIndexCheck {
    private static int failures;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("detraly-index").toFile();
        File a = jar(dir, "a.jar", "com/acme/Shop.class", "com/acme/Shop$Item.class", "shaded/lib/Util.class",
                "META-INF/versions/9/com/acme/Skip.class", "plugin.yml");
        File b = jar(dir, "b.jar", "com/example/Edit.class", "shaded/lib/Util.class", "shaded/lib/OnlyB.class");
        File big = jar(dir, "big.jar", "com/big/X.class");
        File bad = new File(dir, "bad.jar");
        Files.writeString(bad.toPath(), "not a zip");

        Map<String, Set<String>> reuse = new HashMap<>();
        PluginClassIndex index = PluginClassIndex.build(List.of(new PluginClassIndex.Plugin("Acme", a),
                new PluginClassIndex.Plugin("Edit", b), new PluginClassIndex.Plugin("Bad", bad),
                new PluginClassIndex.Plugin("Gone", new File(dir, "missing.jar"))), reuse);

        check("class", "Acme".equals(index.pluginOf("com.acme.Shop")));
        check("inner class", "Acme".equals(index.pluginOf("com.acme.Shop$Item")));
        check("lambda", "Acme".equals(index.pluginOf("com.acme.Shop$$Lambda/0x0000000800c01234")));
        check("other plugin", "Edit".equals(index.pluginOf("com.example.Edit")));
        check("shared package, class in both: first", "Acme".equals(index.pluginOf("shaded.lib.Util")));
        check("shared package, class only in the second", "Edit".equals(index.pluginOf("shaded.lib.OnlyB")));
        check("shared package, class in neither", index.pluginOf("shaded.lib.Nope") == null);
        check("only packages are kept", reuse.values().stream().allMatch(s -> !s.contains("com.acme.Shop")));
        check("unknown", index.pluginOf("net.minecraft.server.MinecraftServer") == null);
        check("META-INF skipped", index.pluginOf("META-INF.versions.9.com.acme.Skip") == null);
        check("unreadable jars skipped", reuse.size() == 2);

        Set<String> before = null;
        for (Map.Entry<String, Set<String>> e : reuse.entrySet()) {
            if (e.getKey().contains("a.jar")) {
                before = e.getValue();
            }
        }
        PluginClassIndex again = PluginClassIndex.build(List.of(new PluginClassIndex.Plugin("Acme", a)), reuse);
        check("reuse keeps one jar", reuse.size() == 1);
        check("reuse same set", before != null && reuse.values().iterator().next() == before);
        check("rebuilt works", "Acme".equals(again.pluginOf("com.acme.Shop")) && again.pluginOf("com.example.Edit") == null);

        File huge = new File(dir, "huge.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(huge))) {
            for (int i = 0; i <= PluginClassIndex.MAX_JAR_ENTRIES; i++) {
                out.putNextEntry(new ZipEntry("p/C" + i + ".class"));
                out.closeEntry();
            }
        }
        check("huge jar skipped", PluginClassIndex.readJar(huge) == PluginClassIndex.TOO_BIG);
        Map<String, Set<String>> hugeReuse = new HashMap<>();
        PluginClassIndex withHuge = PluginClassIndex.build(List.of(new PluginClassIndex.Plugin("Huge", huge),
                new PluginClassIndex.Plugin("Acme", a)), hugeReuse);
        check("huge plugin not matched", withHuge.pluginOf("p.C1") == null && "Acme".equals(withHuge.pluginOf("com.acme.Shop")));
        check("skip is remembered", hugeReuse.size() == 2 && hugeReuse.containsValue(PluginClassIndex.TOO_BIG));
        PluginClassIndex.build(List.of(new PluginClassIndex.Plugin("Huge", huge)), hugeReuse);
        check("skip is kept on rebuild", hugeReuse.size() == 1 && hugeReuse.containsValue(PluginClassIndex.TOO_BIG));
        check("small jar read", PluginClassIndex.readJar(big).contains("com.big"));

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("PluginClassIndexCheck: all passed");
    }

    private static File jar(File dir, String name, String... entries) throws Exception {
        File file = new File(dir, name);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(1);
                out.closeEntry();
            }
        }
        return file;
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

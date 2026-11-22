package com.detraly.analytics.pulse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PluginClassIndex {
    static final int MAX_JAR_ENTRIES = 50_000;
    static final int MAX_TOTAL_STRINGS = 100_000;

    static final Set<String> TOO_BIG = Collections.unmodifiableSet(new HashSet<>());

    record Plugin(String name, File jar) {
    }

    private record Entry(String name, File jar, Set<String> packages) {
    }

    private final List<Entry> entries = new ArrayList<>();

    private PluginClassIndex() {
    }

    static PluginClassIndex build(List<Plugin> plugins, Map<String, Set<String>> reuse) {
        PluginClassIndex index = new PluginClassIndex();
        Map<String, Set<String>> used = new HashMap<>();
        int total = 0;
        for (Plugin plugin : plugins) {
            String key = key(plugin.jar());
            Set<String> packages = used.get(key);
            if (packages == null) {
                packages = reuse.get(key);
            }
            if (packages == null) {
                packages = readJar(plugin.jar());
            }
            if (packages == null) {
                continue;
            }
            used.put(key, packages);
            if (packages == TOO_BIG || total + packages.size() > MAX_TOTAL_STRINGS) {
                continue;
            }
            total += packages.size();
            index.entries.add(new Entry(plugin.name(), plugin.jar(), packages));
        }
        reuse.clear();
        reuse.putAll(used);
        return index;
    }

    String pluginOf(String className) {

        int cut = className.indexOf("$$");
        String name = cut > 0 ? className.substring(0, cut) : className;
        int slash = name.indexOf('/');
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        String pkg = packageOf(name);
        Entry first = null;
        List<Entry> several = null;
        for (Entry entry : entries) {
            if (!entry.packages().contains(pkg)) {
                continue;
            }
            if (first == null) {
                first = entry;
            } else {
                if (several == null) {
                    several = new ArrayList<>();
                    several.add(first);
                }
                several.add(entry);
            }
        }
        if (several == null) {
            return first == null ? null : first.name();
        }

        String path = name.replace('.', '/') + ".class";
        for (Entry entry : several) {
            try (ZipFile zip = new ZipFile(entry.jar())) {
                if (zip.getEntry(path) != null) {
                    return entry.name();
                }
            } catch (IOException | RuntimeException e) {

            }
        }
        return null;
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static String key(File jar) {
        return jar.getAbsolutePath() + "|" + jar.length() + "|" + jar.lastModified();
    }

    static Set<String> readJar(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            if (zip.size() > MAX_JAR_ENTRIES) {
                return TOO_BIG;
            }
            Set<String> packages = new HashSet<>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                String entry = all.nextElement().getName();
                if (entry.endsWith(".class") && !entry.startsWith("META-INF/")) {
                    int slash = entry.lastIndexOf('/');
                    packages.add(slash < 0 ? "" : entry.substring(0, slash).replace('/', '.'));
                }
            }
            return packages;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}

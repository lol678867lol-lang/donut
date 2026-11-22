package de.payfx;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Verlauf aller erhaltenen Zahlungen (gespeichert in config/payfx-history.txt). */
final class History {
    private History() {}

    record Entry(long ts, String name, double amount) {}

    static final List<Entry> LIST = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("payfx-history.txt");
    }

    static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.split(";");
                if (p.length < 3) continue;
                LIST.add(new Entry(Long.parseLong(p[0]), p[1], Double.parseDouble(p[2])));
            }
            while (LIST.size() > 5000) LIST.remove(0);
        } catch (Exception ignored) {
        }
    }

    static void add(String name, double amount) {
        Entry e = new Entry(System.currentTimeMillis(), name, amount);
        LIST.add(e);
        try {
            Files.writeString(file(), e.ts() + ";" + e.name() + ";" + e.amount() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    static void clear() {
        LIST.clear();
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) {
        }
    }

    /** Summe pro Spieler, absteigend sortiert. */
    static List<Map.Entry<String, Double>> totals() {
        Map<String, Double> m = new HashMap<>();
        for (Entry e : LIST) m.merge(e.name(), e.amount(), Double::sum);
        List<Map.Entry<String, Double>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l;
    }

    static double sum() {
        double s = 0;
        for (Entry e : LIST) s += e.amount();
        return s;
    }
}

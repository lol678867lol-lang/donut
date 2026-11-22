package de.payfx;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every finished auction: item, its worth, winner and what you got. Saved in config/payfx-auctions.txt. */
final class AuctionLog {
    private AuctionLog() {}

    record Rec(long ts, String item, double worth, String winner, double received) {
        double profit() { return received - worth; }
    }

    static final List<Rec> LIST = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("payfx-auctions.txt");
    }

    private static String clean(String s) {
        return s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }

    static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.split(";");
                if (p.length < 5) continue;
                LIST.add(new Rec(Long.parseLong(p[0]), p[1], Double.parseDouble(p[2]), p[3], Double.parseDouble(p[4])));
            }
        } catch (Exception ignored) {
        }
    }

    static void add(String item, double worth, String winner, double received) {
        Rec r = new Rec(System.currentTimeMillis(), clean(item), worth, clean(winner), received);
        LIST.add(r);
        try {
            Files.writeString(file(), r.ts() + ";" + r.item() + ";" + r.worth() + ";" + r.winner() + ";" + r.received() + "\n",
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

    /** Newest first, filtered by item or winner name. */
    static List<Rec> search(String q) {
        String s = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Rec> out = new ArrayList<>();
        for (int i = LIST.size() - 1; i >= 0; i--) {
            Rec r = LIST.get(i);
            if (s.isEmpty() || r.item().toLowerCase(Locale.ROOT).contains(s) || r.winner().toLowerCase(Locale.ROOT).contains(s)) out.add(r);
        }
        return out;
    }
}

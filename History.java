package de.payfx;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ledger of all money in (received payments) and out (sent payments, expenses), saved in config/payfx-history.txt. */
final class History {
    private History() {}

    record Entry(long ts, String name, double amount, boolean income) {}

    static final List<Entry> LIST = new ArrayList<>();
    static long sessionStart = System.currentTimeMillis();

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
                boolean income = p.length < 4 || !p[3].equals("E");
                LIST.add(new Entry(Long.parseLong(p[0]), p[1], Double.parseDouble(p[2]), income));
            }
            while (LIST.size() > 5000) LIST.remove(0);
        } catch (Exception ignored) {
        }
    }

    static void add(String name, double amount, boolean income) {
        Entry e = new Entry(System.currentTimeMillis(), name, amount, income);
        LIST.add(e);
        try {
            Files.writeString(file(), e.ts() + ";" + e.name() + ";" + e.amount() + ";" + (income ? "I" : "E") + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static void rewrite() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Entry e : LIST) sb.append(e.ts()).append(';').append(e.name()).append(';').append(e.amount()).append(';').append(e.income() ? "I" : "E").append('\n');
            Files.writeString(file(), sb.toString());
        } catch (Exception ignored) {
        }
    }

    /** Removes the newest entry (for mistakes). Returns it, or null if the list is empty. */
    static Entry undoLast() {
        if (LIST.isEmpty()) return null;
        Entry e = LIST.remove(LIST.size() - 1);
        rewrite();
        return e;
    }

    static void clear() {
        LIST.clear();
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) {
        }
    }

    /** Received money per player, highest first. */
    static List<Map.Entry<String, Double>> totals() {
        Map<String, Double> m = new HashMap<>();
        for (Entry e : LIST) if (e.income()) m.merge(e.name(), e.amount(), Double::sum);
        List<Map.Entry<String, Double>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l;
    }

    static long startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** Sum of income (true) or expenses (false) since the given time. */
    static double sum(boolean income, long since) {
        double s = 0;
        for (Entry e : LIST) if (e.income() == income && e.ts() >= since) s += e.amount();
        return s;
    }

    static double tax(long since) {
        return sum(true, since) * Cfg.taxPercent / 100.0;
    }

    /** Income minus tax minus expenses. */
    static double net(long since) {
        return sum(true, since) - tax(since) - sum(false, since);
    }

    /** Pay list on the screen: the last 5 payments (green = received, red = sent), filtered by Cfg.payListMode. */
    static void payList(GuiGraphics g, Minecraft mc) {
        String mode = Cfg.payListMode;
        if (mode.equals("off")) return;
        int y = Cfg.profitHud ? 20 : 6;
        int shown = 0;
        for (int i = LIST.size() - 1; i >= 0 && shown < 5; i--) {
            Entry e = LIST.get(i);
            if (e.name().startsWith("Auction: ")) continue;
            if (e.income() ? mode.equals("sent") : mode.equals("received")) continue;
            String s = (e.income() ? "+" : "-") + PayFx.fmt(e.amount()) + "  " + e.name();
            g.drawString(mc.font, s, 6, y, e.income() ? 0xFF66FF99 : 0xFFFF6A6A);
            y += 10;
            shown++;
        }
    }

    /** Small line on the screen: profit of this session. */
    static void hud(GuiGraphics g, Minecraft mc) {
        if (!Cfg.profitHud) return;
        double n = net(sessionStart);
        String s = "Session profit: " + PayFx.fmtSigned(n);
        g.drawString(mc.font, s, 6, 6, n >= 0 ? 0xFF66FF99 : 0xFFFF6A6A);
    }
}

package de.payfx;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Einstellungen (werden in config/payfx.properties gespeichert). */
final class Cfg {
    private Cfg() {}

    static final String DEFAULT_REGEX = "(?i)(\\w{3,16})\\s+(?:paid|sent|gave|payed)\\s+you\\s+\\$?\\s*([\\d.,]+)\\s*([kmbt]?)\\b";

    static boolean effects = true;       // Effekte bei erhaltenen Zahlungen
    static boolean payWarn = true;       // Rueckfrage bei grossen /pay
    static boolean ahConfirm = true;     // Rueckfrage bei teuren Kaeufen im AH
    static boolean auctionShow = true;   // Auktion (Item + Anzeige) zeigen
    static boolean tShortcut = true;     // T oeffnet den Chat mit /ah <Block>
    static boolean needMiddle = false;   // Shortcut nur bei gedruecktem Mausrad
    static String shortMode = "ah";      // "ah" oder "order"
    static String ahTitle = "auction";   // Teil des Fenstertitels vom Auktionshaus
    static boolean sounds = true;
    static boolean flash = true;
    static boolean shake = true;
    static double auctionDist = 2.5;
    static double auctionMinutes = 0;
    static double auctionMin = 0;        // Mindestgebot
    static boolean auctionSay = false;   // Fuehrung im oeffentlichen Chat ansagen
    /** Betrag, ab dem Stufe 1 (klein) ... 5 (crazy) beginnt (Effekt gilt fuer Betraege darueber). */
    static double[] tiers = {500_000, 5_000_000, 25_000_000, 250_000_000, 1_000_000_000};
    static boolean[] tierOn = {true, true, true, true, true};
    static double payLimit = 25_000_000;
    static double ahLimit = 5_000_000;
    static String regex = DEFAULT_REGEX;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("payfx.properties");
    }

    static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            }
            effects = Boolean.parseBoolean(p.getProperty("effects", "true"));
            payWarn = Boolean.parseBoolean(p.getProperty("payWarn", "true"));
            ahConfirm = Boolean.parseBoolean(p.getProperty("ahConfirm", "true"));
            auctionShow = Boolean.parseBoolean(p.getProperty("auctionShow", "true"));
            tShortcut = Boolean.parseBoolean(p.getProperty("tShortcut", "true"));
            needMiddle = Boolean.parseBoolean(p.getProperty("needMiddle", "false"));
            shortMode = p.getProperty("shortMode", "ah");
            ahTitle = p.getProperty("ahTitle", "auction");
            payLimit = Double.parseDouble(p.getProperty("payLimit", "25000000"));
            ahLimit = Double.parseDouble(p.getProperty("ahLimit", "5000000"));
            regex = p.getProperty("regex", DEFAULT_REGEX);
            sounds = Boolean.parseBoolean(p.getProperty("sounds", "true"));
            flash = Boolean.parseBoolean(p.getProperty("flash", "true"));
            shake = Boolean.parseBoolean(p.getProperty("shake", "true"));
            auctionDist = Double.parseDouble(p.getProperty("auctionDist", "2.5"));
            auctionMinutes = Double.parseDouble(p.getProperty("auctionMinutes", "0"));
            auctionMin = Double.parseDouble(p.getProperty("auctionMin", "0"));
            auctionSay = Boolean.parseBoolean(p.getProperty("auctionSay", "false"));
            String[] t = p.getProperty("tiers", "500000,5000000,25000000,250000000,1000000000").split(",");
            for (int i = 0; i < 5 && i < t.length; i++) tiers[i] = Double.parseDouble(t[i].trim());
            String on = p.getProperty("tierOn", "11111");
            for (int i = 0; i < 5 && i < on.length(); i++) tierOn[i] = on.charAt(i) == '1';
        } catch (Exception ignored) {
        }
    }

    static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("effects", Boolean.toString(effects));
            p.setProperty("payWarn", Boolean.toString(payWarn));
            p.setProperty("ahConfirm", Boolean.toString(ahConfirm));
            p.setProperty("auctionShow", Boolean.toString(auctionShow));
            p.setProperty("tShortcut", Boolean.toString(tShortcut));
            p.setProperty("needMiddle", Boolean.toString(needMiddle));
            p.setProperty("shortMode", shortMode);
            p.setProperty("ahTitle", ahTitle);
            p.setProperty("payLimit", Double.toString(payLimit));
            p.setProperty("ahLimit", Double.toString(ahLimit));
            p.setProperty("regex", regex);
            p.setProperty("sounds", Boolean.toString(sounds));
            p.setProperty("flash", Boolean.toString(flash));
            p.setProperty("shake", Boolean.toString(shake));
            p.setProperty("auctionDist", Double.toString(auctionDist));
            p.setProperty("auctionMinutes", Double.toString(auctionMinutes));
            p.setProperty("auctionMin", Double.toString(auctionMin));
            p.setProperty("auctionSay", Boolean.toString(auctionSay));
            StringBuilder tb = new StringBuilder();
            StringBuilder on = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                tb.append(i > 0 ? "," : "").append((long) tiers[i]);
                on.append(tierOn[i] ? '1' : '0');
            }
            p.setProperty("tiers", tb.toString());
            p.setProperty("tierOn", on.toString());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "PayFX");
            }
        } catch (Exception ignored) {
        }
    }
}

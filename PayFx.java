package de.payfx;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PayFX: Wenn dir jemand Geld bezahlt, gibt es einen Effekt - je nach Betrag.
 *   bis 500k      kein Effekt
 *   501k - 5m     kleiner Effekt
 *   5m - 25m      mittlerer Effekt
 *   25m - 250m    grosser Effekt
 *   250m - 1b     sehr grosser Effekt
 *   ueber 1b      verrueckter Effekt
 * Befehle: /payfx test <betrag>, /payfx debug, /payfx regex [muster], /payfx reset
 */
public class PayFx implements ClientModInitializer {
    enum Tier {
        NONE(0), SMALL(40), MEDIUM(80), LARGE(140), HUGE(240), CRAZY(400);
        final int ticks;
        Tier(int t) { ticks = t; }
    }

    private static Pattern pattern = Pattern.compile(Cfg.DEFAULT_REGEX);
    static boolean debug;
    static boolean learn;
    static boolean learnExpense;
    private static Pattern expensePattern;
    private static final Random RNG = new Random();

    // laufender Effekt
    private static Tier tier = Tier.NONE;
    private static int age;
    private static String payer = "";
    private static double amount;

    // ------------------------------------------------------------------ Betrag und Stufe

    static Tier tierOf(double a) {
        if (a <= Cfg.tiers[0]) return Tier.NONE;
        if (a <= Cfg.tiers[1]) return Tier.SMALL;
        if (a <= Cfg.tiers[2]) return Tier.MEDIUM;
        if (a <= Cfg.tiers[3]) return Tier.LARGE;
        if (a <= Cfg.tiers[4]) return Tier.HUGE;
        return Tier.CRAZY;
    }

    static double parse(String num, String suffix) {
        String n = num.replace(",", "");
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty()) return -1;
        double v;
        try {
            v = Double.parseDouble(n);
        } catch (NumberFormatException e) {
            return -1;
        }
        switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k": return v * 1e3;
            case "m": return v * 1e6;
            case "b": return v * 1e9;
            case "t": return v * 1e12;
            default: return v;
        }
    }

    /** "5m", "250k", "1.5b", "$30m" or "1200000" -> number, -1 on error. */
    static double parseAmount(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace("$", "").trim();
        if (s.isEmpty()) return -1;
        String suffix = s.substring(s.length() - 1);
        if (suffix.length() == 1 && "kmbt".contains(suffix)) return parse(s.substring(0, s.length() - 1), suffix);
        return parse(s, "");
    }

    static String fmtSigned(double v) {
        return (v < 0 ? "-" : "+") + fmt(Math.abs(v));
    }

    static String fmt(double a) {
        if (a >= 1e12) return String.format(Locale.ROOT, "$%.2fT", a / 1e12);
        if (a >= 1e9) return String.format(Locale.ROOT, "$%.2fB", a / 1e9);
        if (a >= 1e6) return String.format(Locale.ROOT, "$%.2fM", a / 1e6);
        if (a >= 1e3) return String.format(Locale.ROOT, "$%.1fK", a / 1e3);
        return String.format(Locale.ROOT, "$%.0f", a);
    }

    // ------------------------------------------------------------------ Nachrichten

    /** Muster setzen (aus dem Menue). false = ungueltig. */
    static boolean setRegex(String r) {
        try {
            pattern = Pattern.compile(r);
            Cfg.regex = r;
            Cfg.save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void say(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[PayFX] " + s), false);
    }

    private static String lastText = "";
    private static long lastAt;

    private static void onMessage(String text) {
        Minecraft mc = Minecraft.getInstance();
        // The same message can arrive twice (chat + action bar) - only count it once
        long now = System.currentTimeMillis();
        if (text.equals(lastText) && now - lastAt < 1500) return;
        lastText = text;
        lastAt = now;
        if (debug && (text.contains("$") || text.toLowerCase(Locale.ROOT).contains("pay"))) say(mc, "Message: " + text);
        if (learn && text.contains("$")) {
            learnFrom(mc, text);
            return;
        }
        if (expensePattern != null) {
            Matcher em = expensePattern.matcher(text);
            if (em.find()) {
                try {
                    double ea = parse(em.group("num"), em.group("suf") == null ? "" : em.group("suf"));
                    String en = "?";
                    try {
                        en = em.group("name");
                    } catch (IllegalArgumentException ignored) {
                    }
                    if (ea > 0 && !Guard.isEchoOfOwnPay(ea)) History.add(en == null ? "?" : en, ea, false);
                } catch (IllegalArgumentException ignored) {
                }
                return;
            }
        }
        Matcher m = pattern.matcher(text);
        if (!m.find()) return;
        String who, num, suf;
        try {
            who = m.group("name");
            num = m.group("num");
            suf = m.group("suf");
        } catch (IllegalArgumentException e) {
            if (m.groupCount() < 3) return;
            who = m.group(1);
            num = m.group(2);
            suf = m.group(3);
        }
        if (who == null || num == null) return;
        double a = parse(num, suf == null ? "" : suf);
        if (a < 0) return;
        History.add(who, a, true);
        Auction.pay(who, a);
        if (Cfg.effects) trigger(mc, who, a);
    }

    private static final java.util.Set<String> STOP = java.util.Set.of("paid", "sent", "gave", "you", "your", "received", "from", "has", "have",
            "the", "to", "payed", "pay", "money", "of", "and", "was", "were", "just", "got", "get", "new", "player");

    /** Lernmodus: aus einer echten Zahlungsnachricht ein Muster bauen. */
    private static void learnFrom(Minecraft mc, String text) {
        Matcher am = Pattern.compile("\\$\\s*([\\d.,]+)\\s*([kmbtKMBT]?)").matcher(text);
        if (!am.find()) return;
        Matcher nm = Pattern.compile("\\b\\w{3,16}\\b").matcher(text);
        int ns = -1, ne = -1;
        boolean after = text.toLowerCase(Locale.ROOT).matches("^\\W*you\\b.*") || text.toLowerCase(Locale.ROOT).contains(" from ");
        while (nm.find()) {
            String w = nm.group().toLowerCase(Locale.ROOT);
            if (STOP.contains(w) || w.matches("[\\d.,]+[kmbt]?")) continue;
            boolean isAfter = nm.start() > am.end();
            if (after != isAfter) continue;
            ns = nm.start();
            ne = nm.end();
            break;
        }
        if (ns < 0) {
            say(mc, "Could not detect the name. Message: " + text);
            learn = false;
            return;
        }
        String nameRx = "(?<name>\\w{3,16})";
        String amtRx = "\\$\\s*(?<num>[\\d.,]+)\\s*(?<suf>[kmbtKMBT]?)";
        String rx;
        if (ne <= am.start()) {
            rx = nameRx + Pattern.quote(text.substring(ne, am.start())) + amtRx;
        } else {
            rx = amtRx + Pattern.quote(text.substring(am.end(), ns)) + nameRx;
        }
        try {
            if (learnExpense) {
                expensePattern = Pattern.compile(rx);
                Cfg.expenseRegex = rx;
            } else {
                pattern = Pattern.compile(rx);
                Cfg.regex = rx;
            }
            Cfg.save();
            learn = false;
            say(mc, "Learned! Name '" + text.substring(ns, ne) + "', amount '" + am.group(1) + am.group(2) + "'. "
                    + (learnExpense ? "Messages like this now count as an expense." : "Pattern saved."));
        } catch (Exception e) {
            say(mc, "Learning failed: " + e.getMessage());
            learn = false;
        }
    }

    private static final java.util.ArrayDeque<Object[]> QUEUE = new java.util.ArrayDeque<>();

    static void trigger(Minecraft mc, String who, double a) {
        Tier t = tierOf(a);
        if (debug) say(mc, "Payment detected: " + who + " " + fmt(a) + " -> " + t);
        if (t == Tier.NONE || mc.player == null || !Cfg.tierOn[t.ordinal() - 1]) return;
        // An effect is still running: a bigger one takes over, a smaller one waits its turn
        if (tier != Tier.NONE) {
            if (t.ordinal() > tier.ordinal()) {
                start(mc, who, a, t);
            } else if (QUEUE.size() < 8) {
                QUEUE.add(new Object[]{who, a});
            }
            return;
        }
        start(mc, who, a, t);
    }

    private static void start(Minecraft mc, String who, double a, Tier t) {
        tier = t;
        age = 0;
        payer = who;
        amount = a;
        sound(mc, t == Tier.SMALL ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.PLAYER_LEVELUP, 1.0F);
        mc.gui.setTimes(5, Math.min(60, t.ticks / 2), 15);
        if (t != Tier.SMALL) {
            mc.gui.setSubtitle(Component.literal(who + " paid you " + fmt(a)).withStyle(Style.EMPTY.withColor(0xFFE066)));
            mc.gui.setTitle(Component.literal(titleOf(t)).withStyle(Style.EMPTY.withColor(colorOf(t))));
        } else {
            // Action bar text is easily overwritten by the server, so also write it to the chat
            mc.gui.setOverlayMessage(Component.literal("+" + fmt(a) + " from " + who).withStyle(Style.EMPTY.withColor(0x66FF99)), false);
            say(mc, "+" + fmt(a) + " from " + who);
        }
    }

    private static String titleOf(Tier t) {
        switch (t) {
            case MEDIUM: return "PAYMENT!";
            case LARGE: return "BIG PAYMENT!";
            case HUGE: return "HUGE PAYMENT!!";
            default: return "JACKPOT!!!";
        }
    }

    private static int colorOf(Tier t) {
        switch (t) {
            case SMALL: return 0x66FF99;
            case MEDIUM: return 0xFFD166;
            case LARGE: return 0xFF9A3C;
            case HUGE: return 0x4DEEEA;
            default: return 0xFF4DFF;
        }
    }

    private static int hsv(float h) {
        float r = Math.max(0f, Math.min(1f, Math.abs(((h * 6f) % 6f) - 3f) - 1f));
        float g = Math.max(0f, Math.min(1f, 2f - Math.abs(((h * 6f) % 6f) - 2f)));
        float b = Math.max(0f, Math.min(1f, 2f - Math.abs(((h * 6f) % 6f) - 4f)));
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static void sound(Minecraft mc, SoundEvent s, float pitch) {
        if (!Cfg.sounds) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(s, pitch));
    }

    private static void burst(Minecraft mc, ParticleOptions p, int n, double radius, double up, double speed) {
        LocalPlayer pl = mc.player;
        for (int i = 0; i < n; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double r = radius * (0.3 + RNG.nextDouble() * 0.7);
            mc.level.addParticle(p, pl.getX() + Math.cos(ang) * r, pl.getY() + up + RNG.nextDouble() * 2.0, pl.getZ() + Math.sin(ang) * r,
                    (RNG.nextDouble() - 0.5) * speed, RNG.nextDouble() * speed, (RNG.nextDouble() - 0.5) * speed);
        }
    }

    // ------------------------------------------------------------------ Effekte pro Tick

    private static void tick(Minecraft mc) {
        Auction.tick(mc);
        if (tier == Tier.NONE || mc.player == null || mc.level == null) return;
        age++;
        if (age > tier.ticks) {
            tier = Tier.NONE;
            Object[] next = QUEUE.poll();
            if (next != null) trigger(mc, (String) next[0], (Double) next[1]);
            return;
        }
        LocalPlayer p = mc.player;
        if (tier.ordinal() >= Tier.LARGE.ordinal()) {
            double ang = age * 0.45;
            for (int k = 0; k < 2; k++) {
                double a = ang + k * Math.PI;
                mc.level.addParticle(ParticleTypes.END_ROD, p.getX() + Math.cos(a) * 1.4, p.getY() + (age % 40) / 40.0 * 2.2,
                        p.getZ() + Math.sin(a) * 1.4, 0, 0.02, 0);
            }
            if (age % 20 == 0) {
                for (int i = 0; i < 24; i++) {
                    double a = i * Math.PI / 12.0;
                    double r = 0.6 + age / 8.0;
                    mc.level.addParticle(ParticleTypes.FIREWORK, p.getX() + Math.cos(a) * r, p.getY() + 0.2, p.getZ() + Math.sin(a) * r,
                            Math.cos(a) * 0.1, 0.02, Math.sin(a) * 0.1);
                }
            }
        }
        switch (tier) {
            case SMALL:
                if (age % 4 == 0) burst(mc, ParticleTypes.HAPPY_VILLAGER, 4, 1.2, 0.2, 0.1);
                break;
            case MEDIUM:
                if (age == 1) sound(mc, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
                if (age % 2 == 0) burst(mc, ParticleTypes.TOTEM_OF_UNDYING, 6, 1.5, 0.2, 0.4);
                break;
            case LARGE:
                if (age == 1) sound(mc, SoundEvents.FIREWORK_ROCKET_BLAST, 1.0F);
                if (age == 10) sound(mc, SoundEvents.FIREWORK_ROCKET_BLAST, 1.2F);
                burst(mc, ParticleTypes.FIREWORK, 4, 2.0, 0.2, 0.5);
                if (age % 3 == 0) burst(mc, ParticleTypes.TOTEM_OF_UNDYING, 5, 2.0, 0.2, 0.5);
                break;
            case HUGE:
                if (age == 1) {
                    sound(mc, SoundEvents.TOTEM_USE, 1.0F);
                    sound(mc, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0F);
                }
                if (age % 40 == 0) sound(mc, SoundEvents.FIREWORK_ROCKET_BLAST, 0.8F);
                burst(mc, ParticleTypes.END_ROD, 6, 2.5, 0.0, 0.6);
                burst(mc, ParticleTypes.FIREWORK, 4, 2.5, 0.2, 0.6);
                if (age % 2 == 0) burst(mc, ParticleTypes.TOTEM_OF_UNDYING, 6, 3.0, 0.2, 0.7);
                break;
            default:
                // VERRUECKT: Gewitter, Explosionen, Konfetti, Kamera wackelt, Regenbogen-Titel
                if (age == 1) sound(mc, SoundEvents.TOTEM_USE, 0.8F);
                if (age % 20 == 1) sound(mc, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7F + RNG.nextFloat() * 0.6F);
                if (age % 15 == 5) sound(mc, SoundEvents.FIREWORK_ROCKET_BLAST, 0.6F + RNG.nextFloat() * 0.8F);
                burst(mc, ParticleTypes.TOTEM_OF_UNDYING, 8, 4.0, 0.0, 0.9);
                burst(mc, ParticleTypes.FIREWORK, 8, 4.0, 0.0, 0.9);
                burst(mc, ParticleTypes.END_ROD, 6, 4.0, 1.0, 0.5);
                if (age % 12 == 0) burst(mc, ParticleTypes.EXPLOSION_EMITTER, 1, 5.0, 0.5, 0.0);
                if (age < 300 && Cfg.shake) {
                    float k = age < 60 ? 1.0F : 0.5F;
                    p.setYRot(p.getYRot() + (RNG.nextFloat() - 0.5F) * 3.0F * k);
                    p.setXRot(Math.max(-90.0F, Math.min(90.0F, p.getXRot() + (RNG.nextFloat() - 0.5F) * 2.0F * k)));
                }
                if (age % 3 == 0) {
                    mc.gui.setTimes(0, 20, 10);
                    mc.gui.setTitle(Component.literal("JACKPOT!!! " + fmt(amount) + " !!!").withStyle(Style.EMPTY.withColor(hsv((age % 30) / 30.0F))));
                    mc.gui.setSubtitle(Component.literal(payer + " went absolutely crazy").withStyle(Style.EMPTY.withColor(hsv(((age + 15) % 30) / 30.0F))));
                }
                break;
        }
    }

    /** Banner oben: rutscht herein, der Betrag zaehlt hoch. */
    private static void banner(GuiGraphics g, Minecraft mc) {
        if (tier.ordinal() < Tier.MEDIUM.ordinal()) return;
        int W = mc.getWindow().getGuiScaledWidth();
        int stay = tier.ticks - 12;
        int y;
        if (age < 8) y = -22 + age * 3;
        else if (age > stay) y = -(age - stay) * 3;
        else y = 2;
        if (y < -22) return;
        double shown = amount * Math.min(1.0, age / 40.0);
        String s = "+" + fmt(shown) + "   from " + payer;
        int w = mc.font.width(s) + 40;
        int x = (W - w) / 2;
        int c = colorOf(tier);
        if (tier == Tier.CRAZY) c = hsv((age % 40) / 40.0F);
        g.fill(x - 2, y - 2, x + w + 2, y + 20, 0xFF000000 | c);
        g.fill(x, y, x + w, y + 18, 0xF0101018);
        g.drawString(mc.font, s, x + 20, y + 5, 0xFF000000 | c);
    }

    private static void hud(GuiGraphics g, Minecraft mc) {
        Auction.hud(g, mc);
        History.hud(g, mc);
        History.payList(g, mc);
        banner(g, mc);
        if (tier == Tier.NONE || tier == Tier.SMALL || !Cfg.flash) return;
        int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        float fade = 1.0F - (float) age / tier.ticks;
        int alpha;
        int rgb;
        switch (tier) {
            case MEDIUM:
                alpha = (int) (Math.max(0, 1.0F - age / 12.0F) * 70);
                rgb = 0xFFD166;
                break;
            case LARGE:
                alpha = (int) (Math.max(0, 1.0F - age / 25.0F) * 110);
                rgb = 0xFF9A3C;
                break;
            case HUGE:
                alpha = (int) (Math.max(0, 1.0F - age / 50.0F) * 130 + (age % 20 < 3 ? 40 : 0) * fade);
                rgb = 0x4DEEEA;
                break;
            default:
                alpha = 90 + (int) (Math.abs(Math.sin(age * 0.4)) * 80);
                rgb = hsv((age % 40) / 40.0F);
                break;
        }
        if (alpha <= 0) return;
        g.fill(0, 0, W, H, (Math.min(255, alpha) << 24) | rgb);
    }

    // ------------------------------------------------------------------ Start

    @Override
    public void onInitializeClient() {
        Cfg.load();
        History.load();
        AuctionLog.load();
        try {
            if (!Cfg.expenseRegex.isEmpty()) expensePattern = Pattern.compile(Cfg.expenseRegex);
        } catch (Exception ignored) {
        }
        try {
            pattern = Pattern.compile(Cfg.regex);
        } catch (Exception e) {
            pattern = Pattern.compile(Cfg.DEFAULT_REGEX);
        }

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("payfx", "main"));
        KeyMapping openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.payfx.gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, category));

        KeyMapping openStats = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.payfx.stats", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));

        // System messages and action bar text (many servers send payments there) ...
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
        // ... and normal chat messages
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onMessage(message.getString()));

        // Gesendete Befehle: grosse /pay abfangen
        ClientSendMessageEvents.ALLOW_COMMAND.register(Guard::allowCommand);

        ClientTickEvents.START_CLIENT_TICK.register(Shortcut::poll);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openStats.consumeClick()) {
                if (mc.screen == null && mc.player != null) mc.setScreen(new HistoryScreen());
            }
            while (openGui.consumeClick()) {
                if (mc.screen == null && mc.player != null) mc.setScreen(new PayFxScreen());
            }
            tick(mc);
        });

        // Fenster: T-Shortcut im Chat, Klick-Rueckfrage im Auktionshaus
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            Shortcut.onScreen(client, screen);
            if (debug && screen instanceof AbstractContainerScreen<?>) {
                say(client, "Window title: " + screen.getTitle().getString());
            }
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenMouseEvents.allowMouseClick(screen).register((sc, click) -> Guard.allowClick(sc, click.x(), click.y()));
            }
        });

        Auction.init();

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("payfx", "overlay"),
                (graphics, deltaTracker) -> hud(graphics, Minecraft.getInstance()));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("payfx")
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(ctx -> {
                        double a = parseAmount(StringArgumentType.getString(ctx, "amount"));
                        if (a < 0) {
                            ctx.getSource().sendFeedback(Component.literal("Could not read the amount. Examples: 600k, 5m, 30m, 300m, 2b"));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(Component.literal("Test: " + fmt(a) + " -> tier " + tierOf(a)));
                        trigger(Minecraft.getInstance(), "Tester", a);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("tax")
                    .then(ClientCommandManager.argument("percent", StringArgumentType.word()).executes(ctx -> {
                        try {
                            double t = Double.parseDouble(StringArgumentType.getString(ctx, "percent").replace("%", "").replace(",", "."));
                            if (t < 0 || t > 100) return 0;
                            Cfg.taxPercent = t;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Tax set to " + t + "% (subtracted from received payments in the statistics)."));
                            return 1;
                        } catch (NumberFormatException e) {
                            ctx.getSource().sendFeedback(Component.literal("Use a number, e.g. /payfx tax 5"));
                            return 0;
                        }
                    })))
                .then(ClientCommandManager.literal("expense")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(ctx -> {
                        double a = parseAmount(StringArgumentType.getString(ctx, "amount"));
                        if (a <= 0) return 0;
                        History.add("manual", a, false);
                        ctx.getSource().sendFeedback(Component.literal("Expense recorded: " + fmt(a)));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("income")
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(ctx -> {
                        double a = parseAmount(StringArgumentType.getString(ctx, "amount"));
                        if (a <= 0) return 0;
                        History.add("manual", a, true);
                        ctx.getSource().sendFeedback(Component.literal("Income recorded: " + fmt(a)));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("paylist")
                    .then(ClientCommandManager.argument("mode", StringArgumentType.word()).executes(ctx -> {
                        String md = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
                        if (!java.util.List.of("both", "received", "sent", "off").contains(md)) {
                            ctx.getSource().sendFeedback(Component.literal("Use: /payfx paylist both | received | sent | off"));
                            return 0;
                        }
                        Cfg.payListMode = md;
                        Cfg.save();
                        ctx.getSource().sendFeedback(Component.literal("Pay list: " + md));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("export").executes(ctx -> {
                    String f = History.export();
                    ctx.getSource().sendFeedback(Component.literal(f == null ? "Export failed." : "Exported to " + f + " (open it with Excel)"));
                    return f == null ? 0 : 1;
                }))
                .then(ClientCommandManager.literal("undo").executes(ctx -> {
                    History.Entry e = History.undoLast();
                    ctx.getSource().sendFeedback(Component.literal(e == null ? "Nothing to undo."
                            : "Removed: " + (e.income() ? "income " : "expense ") + fmt(e.amount()) + " (" + e.name() + ")"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("session").executes(ctx -> {
                    History.sessionStart = System.currentTimeMillis();
                    ctx.getSource().sendFeedback(Component.literal("Session profit reset to 0."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("profit").executes(ctx -> {
                    long[] since = {History.sessionStart, History.startOfToday(), 0L};
                    String[] n = {"Session", "Today", "All time"};
                    for (int i = 0; i < 3; i++) {
                        ctx.getSource().sendFeedback(Component.literal(n[i] + ": in " + fmt(History.sum(true, since[i]))
                                + " - tax " + fmt(History.tax(since[i])) + " - out " + fmt(History.sum(false, since[i]))
                                + "  =  " + fmtSigned(History.net(since[i]))));
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("stats").executes(ctx -> {
                    Minecraft m = Minecraft.getInstance();
                    m.execute(() -> m.setScreen(new HistoryScreen()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("learn")
                    .then(ClientCommandManager.literal("expense").executes(ctx -> {
                        learn = true;
                        learnExpense = true;
                        ctx.getSource().sendFeedback(Component.literal("Learn mode (expense): the next message containing $ is learned as a message about money you SPENT (it needs a player name in it, e.g. 'You paid Steve $5M')."));
                        return 1;
                    }))
                    .executes(ctx -> {
                    learnExpense = false;
                    learn = true;
                    ctx.getSource().sendFeedback(Component.literal("Learn mode: have someone pay you now. The next message containing $ is learned as the payment format."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("history").executes(ctx -> {
                    Minecraft m = Minecraft.getInstance();
                    m.execute(() -> m.setScreen(new HistoryScreen()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("yes").executes(ctx -> {
                    if (!Guard.confirm()) ctx.getSource().sendFeedback(Component.literal("Nothing to confirm."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debug = !debug;
                    ctx.getSource().sendFeedback(Component.literal("Debug " + (debug ? "ON: messages containing $ or 'pay' and window titles are shown." : "off")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("limit")
                    .then(ClientCommandManager.literal("pay")
                        .then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(ctx -> {
                            double a = parseAmount(StringArgumentType.getString(ctx, "amount"));
                            if (a < 0) return 0;
                            Cfg.payLimit = a;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Pay warning above " + fmt(a)));
                            return 1;
                        })))
                    .then(ClientCommandManager.literal("ah")
                        .then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(ctx -> {
                            double a = parseAmount(StringArgumentType.getString(ctx, "amount"));
                            if (a < 0) return 0;
                            Cfg.ahLimit = a;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("AH confirmation above " + fmt(a)));
                            return 1;
                        }))))
                .then(ClientCommandManager.literal("ahtitle")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                        Cfg.ahTitle = StringArgumentType.getString(ctx, "text").trim();
                        Cfg.save();
                        ctx.getSource().sendFeedback(Component.literal("Auction house window detected by: '" + Cfg.ahTitle + "'"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("reset").executes(ctx -> {
                    Cfg.regex = Cfg.DEFAULT_REGEX;
                    pattern = Pattern.compile(Cfg.DEFAULT_REGEX);
                    Cfg.save();
                    ctx.getSource().sendFeedback(Component.literal("Pattern reset."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("regex")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("Pattern: " + pattern.pattern() + "   (group 1 name, 2 number, 3 k/m/b/t)"));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("pattern", StringArgumentType.greedyString()).executes(ctx -> {
                        String r = StringArgumentType.getString(ctx, "pattern");
                        try {
                            pattern = Pattern.compile(r);
                            Cfg.regex = r;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Pattern saved."));
                        } catch (Exception e) {
                            ctx.getSource().sendFeedback(Component.literal("Invalid pattern: " + e.getMessage()));
                        }
                        return 1;
                    })))));
    }
}

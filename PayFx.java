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

    /** "5m", "250k", "1.5b", "$30m" oder "1200000" -> Zahl, -1 bei Fehler. */
    static double parseAmount(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace("$", "").trim();
        if (s.isEmpty()) return -1;
        String suffix = s.substring(s.length() - 1);
        if (suffix.length() == 1 && "kmbt".contains(suffix)) return parse(s.substring(0, s.length() - 1), suffix);
        return parse(s, "");
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

    private static void onMessage(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (debug && (text.contains("$") || text.toLowerCase(Locale.ROOT).contains("pay"))) say(mc, "Nachricht: " + text);
        if (learn && text.contains("$")) {
            learnFrom(mc, text);
            return;
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
        History.add(who, a);
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
            say(mc, "Konnte den Namen nicht erkennen. Nachricht: " + text);
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
            pattern = Pattern.compile(rx);
            Cfg.regex = rx;
            Cfg.save();
            learn = false;
            say(mc, "Gelernt! Name '" + text.substring(ns, ne) + "', Betrag '" + am.group(1) + am.group(2) + "'. Muster gespeichert.");
        } catch (Exception e) {
            say(mc, "Lernen fehlgeschlagen: " + e.getMessage());
            learn = false;
        }
    }

    static void trigger(Minecraft mc, String who, double a) {
        Tier t = tierOf(a);
        if (debug) say(mc, "Zahlung erkannt: " + who + " " + fmt(a) + " -> " + t);
        if (t == Tier.NONE || mc.player == null || !Cfg.tierOn[t.ordinal() - 1]) return;
        tier = t;
        age = 0;
        payer = who;
        amount = a;
        sound(mc, t == Tier.SMALL ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.PLAYER_LEVELUP, 1.0F);
        mc.gui.setTimes(5, Math.min(60, t.ticks / 2), 15);
        if (t != Tier.SMALL) {
            mc.gui.setSubtitle(Component.literal(who + " hat dir " + fmt(a) + " gezahlt").withStyle(Style.EMPTY.withColor(0xFFE066)));
            mc.gui.setTitle(Component.literal(titleOf(t)).withStyle(Style.EMPTY.withColor(colorOf(t))));
        } else {
            mc.gui.setOverlayMessage(Component.literal("+" + fmt(a) + " von " + who).withStyle(Style.EMPTY.withColor(0x66FF99)), false);
        }
    }

    private static String titleOf(Tier t) {
        switch (t) {
            case MEDIUM: return "ZAHLUNG!";
            case LARGE: return "GROSSE ZAHLUNG!";
            case HUGE: return "RIESIGE ZAHLUNG!!";
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
                    mc.gui.setSubtitle(Component.literal(payer + " ist verrueckt geworden").withStyle(Style.EMPTY.withColor(hsv(((age + 15) % 30) / 30.0F))));
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
        String s = "+" + fmt(shown) + "   von " + payer;
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
        try {
            pattern = Pattern.compile(Cfg.regex);
        } catch (Exception e) {
            pattern = Pattern.compile(Cfg.DEFAULT_REGEX);
        }

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("payfx", "main"));
        KeyMapping openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.payfx.gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, category));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onMessage(message.getString());
        });

        // Gesendete Befehle: grosse /pay abfangen
        ClientSendMessageEvents.ALLOW_COMMAND.register(Guard::allowCommand);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openGui.consumeClick()) {
                if (mc.screen == null && mc.player != null) mc.setScreen(new PayFxScreen());
            }
            tick(mc);
        });

        // Fenster: T-Shortcut im Chat, Klick-Rueckfrage im Auktionshaus
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            Shortcut.onScreen(client, screen);
            if (debug && screen instanceof AbstractContainerScreen<?>) {
                say(client, "Fenster-Titel: " + screen.getTitle().getString());
            }
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenMouseEvents.allowMouseClick(screen).register((sc, click) -> Guard.allowClick(sc));
            }
        });

        Auction.init();

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("payfx", "overlay"),
                (graphics, deltaTracker) -> hud(graphics, Minecraft.getInstance()));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("payfx")
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(ctx -> {
                        double a = parseAmount(StringArgumentType.getString(ctx, "betrag"));
                        if (a < 0) {
                            ctx.getSource().sendFeedback(Component.literal("Betrag nicht lesbar. Beispiele: 600k, 5m, 30m, 300m, 2b"));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(Component.literal("Test: " + fmt(a) + " -> Stufe " + tierOf(a)));
                        trigger(Minecraft.getInstance(), "Tester", a);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("learn").executes(ctx -> {
                    learn = true;
                    ctx.getSource().sendFeedback(Component.literal("Lernmodus: Lass dir jetzt etwas bezahlen (oder /payfx test geht nicht). Die naechste Nachricht mit $ wird als Zahlungsformat gelernt."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("history").executes(ctx -> {
                    Minecraft m = Minecraft.getInstance();
                    m.execute(() -> m.setScreen(new HistoryScreen()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("ja").executes(ctx -> {
                    if (!Guard.confirm()) ctx.getSource().sendFeedback(Component.literal("Nichts zu bestaetigen."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debug = !debug;
                    ctx.getSource().sendFeedback(Component.literal("Debug " + (debug ? "AN: Nachrichten mit $ oder 'pay' und Fenstertitel werden angezeigt." : "aus")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("limit")
                    .then(ClientCommandManager.literal("pay")
                        .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(ctx -> {
                            double a = parseAmount(StringArgumentType.getString(ctx, "betrag"));
                            if (a < 0) return 0;
                            Cfg.payLimit = a;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Pay-Warnung ab " + fmt(a)));
                            return 1;
                        })))
                    .then(ClientCommandManager.literal("ah")
                        .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(ctx -> {
                            double a = parseAmount(StringArgumentType.getString(ctx, "betrag"));
                            if (a < 0) return 0;
                            Cfg.ahLimit = a;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("AH-Bestaetigung ab " + fmt(a)));
                            return 1;
                        }))))
                .then(ClientCommandManager.literal("ahtitle")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                        Cfg.ahTitle = StringArgumentType.getString(ctx, "text").trim();
                        Cfg.save();
                        ctx.getSource().sendFeedback(Component.literal("Auktionshaus-Fenster erkannt an: '" + Cfg.ahTitle + "'"));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("reset").executes(ctx -> {
                    Cfg.regex = Cfg.DEFAULT_REGEX;
                    pattern = Pattern.compile(Cfg.DEFAULT_REGEX);
                    Cfg.save();
                    ctx.getSource().sendFeedback(Component.literal("Muster zurueckgesetzt."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("regex")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("Muster: " + pattern.pattern() + "   (Gruppe 1 Name, 2 Zahl, 3 k/m/b/t)"));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("muster", StringArgumentType.greedyString()).executes(ctx -> {
                        String r = StringArgumentType.getString(ctx, "muster");
                        try {
                            pattern = Pattern.compile(r);
                            Cfg.regex = r;
                            Cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Muster gespeichert."));
                        } catch (Exception e) {
                            ctx.getSource().sendFeedback(Component.literal("Ungueltiges Muster: " + e.getMessage()));
                        }
                        return 1;
                    })))));
    }
}

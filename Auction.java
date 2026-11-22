package de.payfx;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eigene Auktion: /auction start (Item in der Hand) - das Item schwebt und dreht sich vor dir (nur auf deinem Bildschirm).
 * Jede Zahlung, die du bekommst, wird pro Spieler zusammengezaehlt. Wer am meisten gezahlt hat, fuehrt.
 */
final class Auction {
    private Auction() {}

    private static boolean active;
    private static ItemStack item = ItemStack.EMPTY;
    private static ItemEntity entity;
    private static Vec3 pos = Vec3.ZERO;
    private static long endAt;
    private static double lastMinutes;
    private static final Deque<ItemStack> QUEUE = new ArrayDeque<>();
    private static int lastBeep = -1;
    private static final Map<String, Double> TOTALS = new LinkedHashMap<>();

    static boolean active() {
        return active;
    }

    private static List<Map.Entry<String, Double>> ranking() {
        List<Map.Entry<String, Double>> l = new ArrayList<>(TOTALS.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l;
    }

    /** Fuehrender, aber nur wenn er das Mindestgebot erreicht hat. */
    private static Map.Entry<String, Double> valid() {
        List<Map.Entry<String, Double>> r = ranking();
        if (r.isEmpty() || r.get(0).getValue() < Cfg.auctionMin) return null;
        return r.get(0);
    }

    private static String leaderText() {
        List<Map.Entry<String, Double>> r = ranking();
        if (r.isEmpty()) return "noch kein Gebot" + (Cfg.auctionMin > 0 ? " (min. " + PayFx.fmt(Cfg.auctionMin) + ")" : "");
        if (valid() == null) return r.get(0).getKey() + " " + PayFx.fmt(r.get(0).getValue()) + " (min. " + PayFx.fmt(Cfg.auctionMin) + ")";
        return r.get(0).getKey() + "  " + PayFx.fmt(r.get(0).getValue());
    }

    private static void label() {
        if (entity == null) return;
        entity.setCustomName(Component.literal(item.getHoverName().getString() + "  |  " + leaderText())
                .withStyle(Style.EMPTY.withColor(0xFFD166)));
        entity.setCustomNameVisible(true);
    }

    private static void spawn(Minecraft mc) {
        if (mc.level == null) return;
        entity = new ItemEntity(mc.level, pos.x, pos.y, pos.z, item.copy());
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setPickUpDelay(32767);
        entity.setUnlimitedLifetime();
        label();
        mc.level.addEntity(entity);
    }

    private static void remove() {
        if (entity != null) entity.discard();
        entity = null;
    }

    /** Eine Zahlung ist angekommen. */
    static void pay(String who, double amount) {
        if (!active) return;
        Map.Entry<String, Double> was = valid();
        String before = was == null ? "" : was.getKey();
        TOTALS.merge(who, amount, Double::sum);
        Map.Entry<String, Double> now = valid();
        String after = now == null ? "" : now.getKey();
        if (!after.isEmpty() && !after.equals(before)) {
            Minecraft mm = Minecraft.getInstance();
            if (Cfg.auctionSay && mm.getConnection() != null) {
                mm.getConnection().sendChat("Hoechstgebot auf " + item.getHoverName().getString() + ": " + after + " mit " + PayFx.fmt(TOTALS.get(after)));
            }
            Minecraft m = Minecraft.getInstance();
            if (Cfg.sounds) m.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2F));
            m.gui.setTimes(3, 30, 10);
            m.gui.setTitle(Component.literal(after + " fuehrt!").withStyle(Style.EMPTY.withColor(0x66FF99)));
            m.gui.setSubtitle(Component.literal(PayFx.fmt(TOTALS.get(after))).withStyle(Style.EMPTY.withColor(0xFFD166)));
        }
        label();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Auktion] " + who + " insgesamt " + PayFx.fmt(TOTALS.get(who))
                    + "  -  es fuehrt: " + leaderText()), false);
        }
    }

    /** Auktion beenden und den Gewinner zeigen. */
    static void finish(Minecraft mc) {
        Map.Entry<String, Double> win = valid();
        List<Map.Entry<String, Double>> r = ranking();
        active = false;
        endAt = 0;
        remove();
        String itemName = item.getHoverName().getString();
        if (mc.player != null) {
            if (win == null) {
                mc.player.displayClientMessage(Component.literal("[Auktion] beendet - kein gueltiges Gebot"
                        + (Cfg.auctionMin > 0 ? " (Mindestgebot " + PayFx.fmt(Cfg.auctionMin) + ")" : "") + "."), false);
            } else {
                mc.player.displayClientMessage(Component.literal("[Auktion] beendet: " + win.getKey() + " gewinnt mit " + PayFx.fmt(win.getValue())), false);
                for (int i = 1; i < Math.min(3, r.size()); i++) {
                    mc.player.displayClientMessage(Component.literal("[Auktion] " + (i + 1) + ". " + r.get(i).getKey() + "  " + PayFx.fmt(r.get(i).getValue())), false);
                }
                if (Cfg.sounds) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
                mc.gui.setTimes(5, 80, 20);
                mc.gui.setTitle(Component.literal("GEWINNER: " + win.getKey()).withStyle(Style.EMPTY.withColor(0x66FF99)));
                mc.gui.setSubtitle(Component.literal(PayFx.fmt(win.getValue()) + " - " + itemName).withStyle(Style.EMPTY.withColor(0xFFD166)));
                if (Cfg.auctionSay && mc.getConnection() != null) {
                    mc.getConnection().sendChat("Auktion beendet: " + win.getKey() + " gewinnt " + itemName + " mit " + PayFx.fmt(win.getValue()));
                }
            }
        }
        // Naechstes Item aus der Warteschlange
        if (!QUEUE.isEmpty() && mc.player != null) {
            beginWith(mc, mc.player, QUEUE.poll(), lastMinutes);
            mc.player.displayClientMessage(Component.literal("[Auktion] naechstes Item: " + item.getHoverName().getString()
                    + " (noch " + QUEUE.size() + " in der Warteschlange)"), false);
        }
    }

    private static void begin(Minecraft mc, LocalPlayer p, double minutes) {
        beginWith(mc, p, p.getMainHandItem().copy(), minutes);
    }

    private static void beginWith(Minecraft mc, LocalPlayer p, ItemStack stack, double minutes) {
        remove();
        TOTALS.clear();
        lastMinutes = minutes;
        item = stack;
        Vec3 look = p.getViewVector(1.0F);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
        pos = new Vec3(p.getX() + flat.x * Cfg.auctionDist, p.getY() + 1.3, p.getZ() + flat.z * Cfg.auctionDist);
        endAt = minutes > 0 ? System.currentTimeMillis() + (long) (minutes * 60_000) : 0;
        lastBeep = -1;
        active = true;
    }

    static void tick(Minecraft mc) {
        if (!active) return;
        if (endAt > 0) {
            long left = endAt - System.currentTimeMillis();
            if (left <= 0) {
                finish(mc);
                return;
            }
            int sec = (int) (left / 1000);
            if (sec <= 5 && sec != lastBeep && Cfg.sounds && mc.player != null) {
                lastBeep = sec;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8F + (5 - sec) * 0.1F));
            }
        }
        if (mc.level == null || mc.player == null) {
            entity = null;
            return;
        }
        if (!Cfg.auctionShow) {
            remove();
            return;
        }
        if (entity == null || entity.isRemoved() || entity.level() != mc.level) {
            spawn(mc);
        }
        if (entity != null) {
            entity.setPos(pos.x, pos.y, pos.z);
            entity.setDeltaMovement(Vec3.ZERO);
        }
    }

    static void hud(GuiGraphics g, Minecraft mc) {
        if (!active || !Cfg.auctionShow) return;
        int W = mc.getWindow().getGuiScaledWidth();
        List<Map.Entry<String, Double>> r = ranking();
        int y = 6;
        String head = "AUKTION: " + item.getHoverName().getString();
        if (endAt > 0) {
            long left = Math.max(0, endAt - System.currentTimeMillis()) / 1000;
            head += String.format("  [%d:%02d]", left / 60, left % 60);
        }
        if (!QUEUE.isEmpty()) head += "  (+" + QUEUE.size() + " wartend)";
        g.drawString(mc.font, head, W - mc.font.width(head) - 6, y, 0xFFFFD166);
        y += 11;
        if (r.isEmpty()) {
            String s = "noch kein Gebot";
            g.drawString(mc.font, s, W - mc.font.width(s) - 6, y, 0xFFB8B8C8);
            return;
        }
        for (int i = 0; i < Math.min(5, r.size()); i++) {
            String s = (i == 0 ? "> " : (i + 1) + ". ") + r.get(i).getKey() + "  " + PayFx.fmt(r.get(i).getValue());
            g.drawString(mc.font, s, W - mc.font.width(s) - 6, y, i == 0 ? 0xFF66FF99 : 0xFFFFFFFF);
            y += 10;
        }
    }

    private static int startCmd(java.util.function.Consumer<Component> out, double minutes) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return 0;
        if (p.getMainHandItem().isEmpty()) {
            out.accept(Component.literal("Nimm das Item in die Hand, das du versteigern willst."));
            return 0;
        }
        begin(mc, p, minutes);
        out.accept(Component.literal("Auktion gestartet: " + item.getHoverName().getString()
                + (minutes > 0 ? " (" + (int) minutes + " Min)" : " (ohne Zeit, Ende mit /auction end)")
                + ". Zahlungen an dich werden pro Spieler gezaehlt."));
        return 1;
    }

    static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("auction")
                .then(ClientCommandManager.literal("start")
                    .executes(ctx -> startCmd(ctx.getSource()::sendFeedback, Cfg.auctionMinutes))
                    .then(ClientCommandManager.argument("minuten", IntegerArgumentType.integer(1, 1440))
                        .executes(ctx -> startCmd(ctx.getSource()::sendFeedback, IntegerArgumentType.getInteger(ctx, "minuten")))))
                .then(ClientCommandManager.literal("end").executes(ctx -> {
                    if (!active) {
                        ctx.getSource().sendFeedback(Component.literal("Es laeuft keine Auktion."));
                        return 0;
                    }
                    finish(Minecraft.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("queue").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null || mc.player.getMainHandItem().isEmpty()) {
                        ctx.getSource().sendFeedback(Component.literal("Nimm das Item in die Hand, das du in die Warteschlange legen willst."));
                        return 0;
                    }
                    QUEUE.add(mc.player.getMainHandItem().copy());
                    ctx.getSource().sendFeedback(Component.literal("In die Warteschlange gelegt (" + QUEUE.size() + "). Es startet automatisch, wenn die aktuelle Auktion endet."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("min")
                    .then(ClientCommandManager.argument("betrag", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                        double a = PayFx.parseAmount(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "betrag"));
                        if (a < 0) return 0;
                        Cfg.auctionMin = a;
                        Cfg.save();
                        ctx.getSource().sendFeedback(Component.literal("Mindestgebot: " + PayFx.fmt(a)));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    QUEUE.clear();
                    active = false;
                    remove();
                    ctx.getSource().sendFeedback(Component.literal("Auktion abgebrochen."));
                    return 1;
                }))
                .then(ClientCommandManager.literal("say").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    List<Map.Entry<String, Double>> r = ranking();
                    if (mc.getConnection() == null || r.isEmpty()) {
                        ctx.getSource().sendFeedback(Component.literal("Noch kein Gebot oder nicht verbunden."));
                        return 0;
                    }
                    mc.getConnection().sendChat("Auktion " + item.getHoverName().getString() + ": es fuehrt " + r.get(0).getKey()
                            + " mit " + PayFx.fmt(r.get(0).getValue()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("list").executes(ctx -> {
                    List<Map.Entry<String, Double>> r = ranking();
                    if (r.isEmpty()) ctx.getSource().sendFeedback(Component.literal("Noch kein Gebot."));
                    for (int i = 0; i < r.size(); i++) {
                        ctx.getSource().sendFeedback(Component.literal((i + 1) + ". " + r.get(i).getKey() + "  " + PayFx.fmt(r.get(i).getValue())));
                    }
                    return 1;
                }))));
    }
}

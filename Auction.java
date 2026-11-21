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

import java.util.ArrayList;
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

    private static String leaderText() {
        List<Map.Entry<String, Double>> r = ranking();
        if (r.isEmpty()) return "noch kein Gebot";
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
        String before = ranking().isEmpty() ? "" : ranking().get(0).getKey();
        TOTALS.merge(who, amount, Double::sum);
        String after = ranking().get(0).getKey();
        if (!after.equals(before)) {
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
        List<Map.Entry<String, Double>> r = ranking();
        active = false;
        endAt = 0;
        remove();
        if (mc.player == null) return;
        if (r.isEmpty()) {
            mc.player.displayClientMessage(Component.literal("[Auktion] beendet - es gab kein Gebot."), false);
            return;
        }
        String w = r.get(0).getKey() + " gewinnt mit " + PayFx.fmt(r.get(0).getValue());
        mc.player.displayClientMessage(Component.literal("[Auktion] beendet: " + w), false);
        for (int i = 1; i < Math.min(3, r.size()); i++) {
            mc.player.displayClientMessage(Component.literal("[Auktion] " + (i + 1) + ". " + r.get(i).getKey() + "  " + PayFx.fmt(r.get(i).getValue())), false);
        }
        if (Cfg.sounds) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
        mc.gui.setTimes(5, 80, 20);
        mc.gui.setTitle(Component.literal("GEWINNER: " + r.get(0).getKey()).withStyle(Style.EMPTY.withColor(0x66FF99)));
        mc.gui.setSubtitle(Component.literal(PayFx.fmt(r.get(0).getValue()) + " - " + item.getHoverName().getString()).withStyle(Style.EMPTY.withColor(0xFFD166)));
    }

    private static void begin(Minecraft mc, LocalPlayer p, double minutes) {
        remove();
        TOTALS.clear();
        item = p.getMainHandItem().copy();
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
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
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

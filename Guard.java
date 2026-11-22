package de.payfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rueckfragen: grosse /pay und teure Kaeufe im Auktionshaus muessen bestaetigt werden. */
final class Guard {
    private Guard() {}

    private static String pending;
    private static long pendingAt;
    private static boolean bypass;

    private static Slot lastSlot;
    private static long lastSlotAt;

    private static final Pattern PRICE = Pattern.compile("\\$\\s*([\\d.,]+)\\s*([kmbtKMBT]?)");

    private static void warn(Minecraft mc, String title, String line) {
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal("[PayFX] " + line).withStyle(Style.EMPTY.withColor(0xFF6A6A)), false);
        mc.gui.setTimes(3, 40, 10);
        mc.gui.setTitle(Component.literal(title).withStyle(Style.EMPTY.withColor(0xFF4D4D)));
        mc.gui.setSubtitle(Component.literal(line).withStyle(Style.EMPTY.withColor(0xFFE066)));
    }

    /** Wird fuer jeden gesendeten Befehl aufgerufen (ohne "/"). false = Befehl wird blockiert. */
    static boolean allowCommand(String cmd) {
        if (!Cfg.payWarn) return true;
        if (bypass) {
            bypass = false;
            return true;
        }
        String[] p = cmd.trim().split("\\s+");
        if (p.length < 3 || !p[0].equalsIgnoreCase("pay")) return true;
        double a = PayFx.parseAmount(p[2]);
        if (a < 0 || a <= Cfg.payLimit) return true;
        long now = System.currentTimeMillis();
        if (cmd.equals(pending) && now - pendingAt < 15_000) {
            pending = null;
            return true;
        }
        pending = cmd;
        pendingAt = now;
        warn(Minecraft.getInstance(), "GROSSE ZAHLUNG!",
                "/pay " + p[1] + " " + PayFx.fmt(a) + " - zum Bestaetigen den Befehl nochmal senden oder /payfx ja");
        return false;
    }

    /** /payfx ja */
    static boolean confirm() {
        Minecraft mc = Minecraft.getInstance();
        if (pending == null || System.currentTimeMillis() - pendingAt > 15_000 || mc.getConnection() == null) return false;
        String c = pending;
        pending = null;
        bypass = true;
        mc.getConnection().sendCommand(c);
        return true;
    }

    private static double priceOf(ItemStack st) {
        ItemLore lore = st.get(DataComponents.LORE);
        if (lore == null) return -1;
        double firstAny = -1;
        for (Component line : lore.lines()) {
            String s = line.getString();
            Matcher m = PRICE.matcher(s);
            if (!m.find()) continue;
            double v = PayFx.parse(m.group(1), m.group(2));
            if (v < 0) continue;
            if (s.toLowerCase(Locale.ROOT).contains("price") || s.toLowerCase(Locale.ROOT).contains("preis")) return v;
            if (firstAny < 0) firstAny = v;
        }
        return firstAny;
    }

    /** Slot unter der Maus in einem Kisten-Fenster (Standard-Layout, 176 Pixel breit). */
    private static Slot slotAt(AbstractContainerScreen<?> acs, Screen screen, double mx, double my) {
        if (!(acs.getMenu() instanceof ChestMenu cm)) return null;
        int left = (screen.width - 176) / 2;
        int top = (screen.height - (114 + cm.getRowCount() * 18)) / 2;
        for (Slot s : cm.slots) {
            if (mx >= left + s.x - 1 && mx < left + s.x + 17 && my >= top + s.y - 1 && my < top + s.y + 17) return s;
        }
        return null;
    }

    /** Klick in einem Container-Fenster. false = Klick wird blockiert. */
    static boolean allowClick(Screen screen, double mx, double my) {
        if (!Cfg.ahConfirm) return true;
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return true;
        if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains(Cfg.ahTitle.toLowerCase(Locale.ROOT))) return true;
        Slot s = slotAt(acs, screen, mx, my);
        if (s == null || !s.hasItem()) return true;
        double price = priceOf(s.getItem());
        if (price <= Cfg.ahLimit) return true;
        long now = System.currentTimeMillis();
        if (s == lastSlot && now - lastSlotAt < 10_000) {
            lastSlot = null;
            return true;
        }
        lastSlot = s;
        lastSlotAt = now;
        warn(Minecraft.getInstance(), "TEURER KAUF!",
                s.getItem().getHoverName().getString() + " kostet " + PayFx.fmt(price) + " - nochmal klicken zum Kaufen");
        return false;
    }
}

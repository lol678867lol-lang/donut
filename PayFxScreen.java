package de.payfx;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleFunction;

/** Menue (Taste C): alles einstellen. Klick = an/aus bzw. Wert aendern (links vom Wert = kleiner, rechts = groesser). Mausrad scrollt. */
public class PayFxScreen extends Screen {
    private static final int ROW = 18, W = 300, TOP = 36;
    private static final double[] AMOUNTS = {100e3, 250e3, 500e3, 1e6, 2e6, 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 1e9, 2e9, 5e9, 10e9};
    private static final double[] MINUTES = {0, 1, 2, 3, 5, 10, 15, 30, 60};
    private static final double[] DISTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0};

    private abstract static class Row {
        final String label;
        Row(String label) { this.label = label; }
        String label() { return label; }
        abstract String value();
        abstract int color();
        abstract void click(int dir);
        boolean header() { return false; }
    }

    private final List<Row> rows = new ArrayList<>();
    private double scroll;
    private int x0;

    private Row header(String text) {
        return new Row(text) {
            String value() { return ""; }
            int color() { return 0; }
            void click(int dir) {}
            boolean header() { return true; }
        };
    }

    private Row toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
        return new Row(label) {
            String value() { return get.getAsBoolean() ? "AN" : "AUS"; }
            int color() { return get.getAsBoolean() ? 0xFF5CFF7A : 0xFFFF6A6A; }
            void click(int dir) { set.accept(!get.getAsBoolean()); }
        };
    }

    private Row stepper(String label, double[] list, DoubleSupplier get, DoubleConsumer set, DoubleFunction<String> fmt) {
        return new Row(label) {
            String value() { return "<  " + fmt.apply(get.getAsDouble()) + "  >"; }
            int color() { return 0xFFFFFFFF; }
            void click(int dir) {
                int best = 0;
                for (int i = 0; i < list.length; i++) {
                    if (Math.abs(list[i] - get.getAsDouble()) < Math.abs(list[best] - get.getAsDouble())) best = i;
                }
                int n = Math.max(0, Math.min(list.length - 1, best + dir));
                set.accept(list[n]);
            }
        };
    }

    private void setTier(int i, double v) {
        double lo = i > 0 ? Cfg.tiers[i - 1] : 0;
        double hi = i < 4 ? Cfg.tiers[i + 1] : Double.MAX_VALUE;
        Cfg.tiers[i] = Math.max(lo, Math.min(hi, v));
    }

    public PayFxScreen() {
        super(Component.literal("PayFX"));
        rows.add(header("Zahlungs-Effekte"));
        rows.add(toggle("Effekte insgesamt", () -> Cfg.effects, v -> Cfg.effects = v));
        rows.add(toggle("Sounds", () -> Cfg.sounds, v -> Cfg.sounds = v));
        rows.add(toggle("Bildschirm-Blitz", () -> Cfg.flash, v -> Cfg.flash = v));
        rows.add(toggle("Kamera wackeln (Crazy)", () -> Cfg.shake, v -> Cfg.shake = v));
        rows.add(header("Stufen: Betrag, ab dem die Stufe gilt"));
        String[] names = {"Klein", "Mittel", "Gross", "Sehr gross", "Crazy"};
        for (int i = 0; i < 5; i++) {
            final int k = i;
            rows.add(stepper(names[i] + " ab mehr als", AMOUNTS, () -> Cfg.tiers[k], v -> setTier(k, v), PayFx::fmt));
            rows.add(toggle(names[i] + "-Effekt", () -> Cfg.tierOn[k], v -> Cfg.tierOn[k] = v));
        }
        rows.add(header("Warnungen"));
        rows.add(toggle("Pay-Warnung", () -> Cfg.payWarn, v -> Cfg.payWarn = v));
        rows.add(stepper("  ab mehr als", AMOUNTS, () -> Cfg.payLimit, v -> Cfg.payLimit = v, PayFx::fmt));
        rows.add(toggle("AH-Bestaetigung", () -> Cfg.ahConfirm, v -> Cfg.ahConfirm = v));
        rows.add(stepper("  ab mehr als", AMOUNTS, () -> Cfg.ahLimit, v -> Cfg.ahLimit = v, PayFx::fmt));
        rows.add(header("Auktion"));
        rows.add(toggle("Auktion anzeigen", () -> Cfg.auctionShow, v -> Cfg.auctionShow = v));
        rows.add(stepper("Abstand des Items", DISTS, () -> Cfg.auctionDist, v -> Cfg.auctionDist = v, d -> d + " Bloecke"));
        rows.add(stepper("Dauer (0 = ohne Zeit)", MINUTES, () -> Cfg.auctionMinutes, v -> Cfg.auctionMinutes = v, d -> d == 0 ? "ohne Zeit" : (int) (double) d + " Min"));
        rows.add(header("Shortcut (Taste T)"));
        rows.add(toggle("T-Shortcut (Block im Blick)", () -> Cfg.tShortcut, v -> Cfg.tShortcut = v));
        rows.add(toggle("Nur mit gedruecktem Mausrad", () -> Cfg.needMiddle, v -> Cfg.needMiddle = v));
        rows.add(new Row("Befehl") {
            String value() { return "/" + Cfg.shortMode + "  (klicken)"; }
            int color() { return 0xFFFFD166; }
            void click(int dir) { Cfg.shortMode = Cfg.shortMode.equals("ah") ? "order" : "ah"; }
        });
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int contentH() {
        return rows.size() * ROW;
    }

    private void clampScroll() {
        int max = Math.max(0, contentH() + TOP + 40 - height);
        scroll = Math.max(-max, Math.min(0, scroll));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xA0050510, 0xD0140A28);
        clampScroll();
        x0 = (width - W) / 2;
        int y = TOP + (int) scroll;
        for (Row r : rows) {
            if (y + ROW > TOP - 4 && y < height - 22) {
                if (r.header()) {
                    g.fill(x0, y + 2, x0 + W, y + ROW, 0xFF2A1A4A);
                    g.drawString(font, r.label(), x0 + 6, y + 6, 0xFFFFD166);
                } else {
                    boolean hov = mx >= x0 && mx < x0 + W && my >= y && my < y + ROW - 1;
                    g.fill(x0, y, x0 + W, y + ROW - 1, hov ? 0xFF1E1E2C : 0xFF14141E);
                    g.drawString(font, r.label(), x0 + 8, y + 5, 0xFFFFFFFF);
                    String v = r.value();
                    g.drawString(font, v, x0 + W - 8 - font.width(v), y + 5, r.color());
                }
            }
            y += ROW;
        }
        g.fill(0, 0, width, TOP - 6, 0xE00A0A14);
        g.fill(0, height - 20, width, height, 0xE00A0A14);
        String title = "P A Y F X";
        g.drawString(font, title, (width - font.width(title)) / 2, 12, 0xFFFFD166);
        String hint = "Klick: an/aus oder Wert (links < kleiner, rechts > groesser)   Mausrad: scrollen   C/ESC: zu";
        g.drawString(font, hint, (width - font.width(hint)) / 2, height - 13, 0xFF7A7A90);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (my < TOP - 6 || my > height - 20 || mx < x0 || mx >= x0 + W) return super.mouseClicked(event, doubleClick);
        int i = (int) ((my - TOP - scroll) / ROW);
        if (i >= 0 && i < rows.size()) {
            Row r = rows.get(i);
            if (!r.header()) {
                int dir = mx < x0 + W - 70 ? -1 : 1;
                r.click(dir);
                Cfg.save();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll += scrollY * 24.0;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_C) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}

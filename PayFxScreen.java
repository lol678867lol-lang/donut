package de.payfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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

/** Menu (C key): configure everything. Click = toggle or change a value (left of the value = lower, right = higher). Mouse wheel scrolls. */
public class PayFxScreen extends Screen {
    private static final int ROW = 18, W = 300, TOP = 36;
    private static final double[] AMOUNTS = {100e3, 250e3, 500e3, 1e6, 2e6, 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 1e9, 2e9, 5e9, 10e9};
    private static final double[] TAX = {0, 1, 2, 3, 4, 5, 7.5, 10, 12.5, 15, 20, 25, 30};
    private static final double[] COUNTS = {3, 5, 8, 10};
    private static final double[] SNIPE = {0, 5, 10, 20, 30, 60};
    private static final double[] MINUTES = {0, 1, 2, 3, 5, 10, 15, 30, 60};
    private static final double[] MINBID = {0, 100e3, 500e3, 1e6, 5e6, 10e6, 50e6, 100e6, 500e6, 1e9};
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
    private EditBox ahBox, rxBox;
    private boolean rxOk = true;

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
            String value() { return get.getAsBoolean() ? "ON" : "OFF"; }
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

    private Row action(String label, String valueText, Runnable r) {
        return new Row(label) {
            String value() { return valueText; }
            int color() { return 0xFFFFD166; }
            void click(int dir) { r.run(); }
        };
    }

    private void setTier(int i, double v) {
        double lo = i > 0 ? Cfg.tiers[i - 1] : 0;
        double hi = i < 4 ? Cfg.tiers[i + 1] : Double.MAX_VALUE;
        Cfg.tiers[i] = Math.max(lo, Math.min(hi, v));
    }

    public PayFxScreen() {
        super(Component.literal("PayFX"));
        rows.add(action(">> STATISTICS & CHARTS <<", "open", () -> Minecraft.getInstance().setScreen(new HistoryScreen())));
        rows.add(action(">> AUCTION LIST (search) <<", "open", () -> Minecraft.getInstance().setScreen(new AuctionScreen())));
        rows.add(header("Payment effects"));
        rows.add(toggle("Effects (master switch)", () -> Cfg.effects, v -> Cfg.effects = v));
        rows.add(toggle("Sounds", () -> Cfg.sounds, v -> Cfg.sounds = v));
        rows.add(toggle("Screen flash", () -> Cfg.flash, v -> Cfg.flash = v));
        rows.add(toggle("Camera shake (Crazy)", () -> Cfg.shake, v -> Cfg.shake = v));
        rows.add(header("Tiers: amount above which the tier applies"));
        String[] names = {"Small", "Medium", "Large", "Huge", "Crazy"};
        for (int i = 0; i < 5; i++) {
            final int k = i;
            rows.add(stepper(names[i] + " above", AMOUNTS, () -> Cfg.tiers[k], v -> setTier(k, v), PayFx::fmt));
            rows.add(toggle(names[i] + " effect", () -> Cfg.tierOn[k], v -> Cfg.tierOn[k] = v));
        }
        rows.add(header("Warnings"));
        rows.add(toggle("Pay warning", () -> Cfg.payWarn, v -> Cfg.payWarn = v));
        rows.add(stepper("  above", AMOUNTS, () -> Cfg.payLimit, v -> Cfg.payLimit = v, PayFx::fmt));
        rows.add(toggle("AH confirmation", () -> Cfg.ahConfirm, v -> Cfg.ahConfirm = v));
        rows.add(stepper("  above", AMOUNTS, () -> Cfg.ahLimit, v -> Cfg.ahLimit = v, PayFx::fmt));
        rows.add(header("Auction"));
        rows.add(toggle("Show auction", () -> Cfg.auctionShow, v -> Cfg.auctionShow = v));
        rows.add(stepper("Item distance", DISTS, () -> Cfg.auctionDist, v -> Cfg.auctionDist = v, d -> d + " blocks"));
        rows.add(stepper("Duration (0 = no limit)", MINUTES, () -> Cfg.auctionMinutes, v -> Cfg.auctionMinutes = v, d -> d == 0 ? "no limit" : (int) (double) d + " min"));
        rows.add(stepper("Minimum bid", MINBID, () -> Cfg.auctionMin, v -> Cfg.auctionMin = v, d -> d == 0 ? "none" : PayFx.fmt(d)));
        rows.add(toggle("Announce leader in chat", () -> Cfg.auctionSay, v -> Cfg.auctionSay = v));
        rows.add(stepper("Anti-sniping (extend on late bid)", SNIPE, () -> Cfg.auctionSnipe, v -> Cfg.auctionSnipe = v, d -> d == 0 ? "off" : (int) (double) d + " s"));
        rows.add(header("Profit & statistics"));
        rows.add(stepper("Tax on income", TAX, () -> Cfg.taxPercent, v -> Cfg.taxPercent = v, d -> d + "%"));
        rows.add(toggle("Show session profit on screen", () -> Cfg.profitHud, v -> Cfg.profitHud = v));
        rows.add(new Row("Pay list on screen (last 5)") {
            String value() {
                return switch (Cfg.payListMode) {
                    case "received" -> "received only (green)";
                    case "sent" -> "sent only (red)";
                    case "off" -> "off";
                    default -> "both";
                };
            }
            int color() {
                return switch (Cfg.payListMode) {
                    case "received" -> 0xFF66FF99;
                    case "sent" -> 0xFFFF6A6A;
                    case "off" -> 0xFF7A7A90;
                    default -> 0xFFFFD166;
                };
            }
            void click(int dir) {
                String[] m = {"both", "received", "sent", "off"};
                int i = 0;
                for (int k = 0; k < m.length; k++) if (m[k].equals(Cfg.payListMode)) i = k;
                Cfg.payListMode = m[(i + (dir > 0 ? 1 : m.length - 1)) % m.length];
            }
        });
        rows.add(stepper("Pay list entries", COUNTS, () -> Cfg.payListCount, v -> Cfg.payListCount = v, d -> Integer.toString((int) (double) d)));
        rows.add(new Row("Pay list position") {
            String value() { return Cfg.payListSide.equals("right") ? "top right" : "top left"; }
            int color() { return 0xFFFFD166; }
            void click(int dir) { Cfg.payListSide = Cfg.payListSide.equals("right") ? "left" : "right"; }
        });
        rows.add(action("Export statistics (CSV)", "export", () -> {
            String f = History.export();
            Minecraft m = Minecraft.getInstance();
            PayFx.say(m, f == null ? "Export failed." : "Exported to " + f);
        }));
        rows.add(action("Statistics (profit, history)", "open", () -> {
            Minecraft m = Minecraft.getInstance();
            m.setScreen(new HistoryScreen());
        }));
        rows.add(action("Auction log (worth, search)", "open", () -> {
            Minecraft m = Minecraft.getInstance();
            m.setScreen(new AuctionScreen());
        }));
        rows.add(header("Shortcut (T key)"));
        rows.add(toggle("T shortcut (block in view)", () -> Cfg.tShortcut, v -> Cfg.tShortcut = v));
        rows.add(toggle("Only with mouse wheel pressed", () -> Cfg.needMiddle, v -> Cfg.needMiddle = v));
        rows.add(new Row("Command") {
            String value() { return "/" + Cfg.shortMode + "  (click)"; }
            int color() { return 0xFFFFD166; }
            void click(int dir) { Cfg.shortMode = Cfg.shortMode.equals("ah") ? "order" : "ah"; }
        });
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        ahBox = new EditBox(font, x0, height - 64, W, 14, Component.literal("Auction house title"));
        ahBox.setMaxLength(60);
        ahBox.setValue(Cfg.ahTitle);
        ahBox.setResponder(v -> {
            Cfg.ahTitle = v;
            Cfg.save();
        });
        addWidget(ahBox);
        rxBox = new EditBox(font, x0, height - 38, W, 14, Component.literal("Payment pattern"));
        rxBox.setMaxLength(400);
        rxBox.setValue(Cfg.regex);
        rxBox.setResponder(v -> rxOk = PayFx.setRegex(v));
        addWidget(rxBox);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int contentH() {
        return rows.size() * ROW;
    }

    private void clampScroll() {
        int max = Math.max(0, contentH() + TOP + 90 - height);
        scroll = Math.max(-max, Math.min(0, scroll));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xA0050510, 0xD0140A28);
        clampScroll();
        x0 = (width - W) / 2;
        int y = TOP + (int) scroll;
        for (Row r : rows) {
            if (y + ROW > TOP - 4 && y < height - 80) {
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
        g.fill(0, height - 80, width, height, 0xF00A0A14);
        g.drawString(font, "Auction house window title (part of the title, e.g. auction)", x0, height - 76, 0xFFB8B8C8);
        g.drawString(font, "Payment pattern (regex with name / num / suf)" + (rxOk ? "" : "  - INVALID"), x0, height - 50, rxOk ? 0xFFB8B8C8 : 0xFFFF6A6A);
        ahBox.render(g, mx, my, delta);
        rxBox.render(g, mx, my, delta);
        String title = "P A Y F X";
        g.drawString(font, title, (width - font.width(title)) / 2, 12, 0xFFFFD166);
        String hint = "Click: toggle or change value (left of value = lower, right = higher)   Mouse wheel: scroll   ESC: close";
        g.drawString(font, hint, (width - font.width(hint)) / 2, height - 14, 0xFF7A7A90);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (my < TOP - 6 || my > height - 80 || mx < x0 || mx >= x0 + W) return super.mouseClicked(event, doubleClick);
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
        if (getFocused() instanceof EditBox) return super.keyPressed(event);
        if (event.key() == GLFW.GLFW_KEY_C) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}

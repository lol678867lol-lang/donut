package de.payfx;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Statistics: profit after tax (session / today / all time), top players and the latest payments. */
public class HistoryScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private int bx, by;

    public HistoryScreen() {
        super(Component.literal("PayFX Statistics"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void cell(GuiGraphics g, String s, int rightX, int y, int color) {
        g.drawString(font, s, rightX - font.width(s), y, color);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xB0050510, 0xE0140A28);
        String title = "P A Y F X   S T A T I S T I C S";
        g.drawString(font, title, (width - font.width(title)) / 2, 10, 0xFFFFD166);

        // --- profit table
        int tw = Math.min(380, width - 20);
        int tx = (width - tw) / 2;
        int top = 26;
        long[] since = {History.sessionStart, History.startOfToday(), System.currentTimeMillis() - 7L * 86_400_000L, 0L};
        String[] heads = {"Session", "Today", "7 days", "All time"};
        int colW = (tw - 110) / 4;
        g.fill(tx, top, tx + tw, top + 12, 0xFF2A1A4A);
        for (int c = 0; c < 4; c++) cell(g, heads[c], tx + 110 + colW * (c + 1) - 4, top + 2, 0xFFFFD166);
        String[] labels = {"Income (received)", "Tax (" + trim(Cfg.taxPercent) + "%)", "Income after tax", "Expenses (sent)", "PROFIT / LOSS"};
        for (int r = 0; r < labels.length; r++) {
            int y = top + 16 + r * 12;
            boolean last = r == labels.length - 1;
            if (last) g.fill(tx, y - 2, tx + tw, y + 10, 0xFF14141E);
            g.drawString(font, labels[r], tx + 4, y, last ? 0xFFFFFFFF : 0xFFB8B8C8);
            for (int c = 0; c < 4; c++) {
                double inc = History.sum(true, since[c]);
                double tax = History.tax(since[c]);
                double exp = History.sum(false, since[c]);
                double net = inc - tax - exp;
                int rx = tx + 110 + colW * (c + 1) - 4;
                switch (r) {
                    case 0 -> cell(g, PayFx.fmt(inc), rx, y, 0xFFFFFFFF);
                    case 1 -> cell(g, "-" + PayFx.fmt(tax), rx, y, 0xFFFFAA44);
                    case 2 -> cell(g, PayFx.fmt(inc - tax), rx, y, 0xFFFFFFFF);
                    case 3 -> cell(g, "-" + PayFx.fmt(exp), rx, y, 0xFFFF6A6A);
                    default -> cell(g, PayFx.fmtSigned(net), rx, y, net >= 0 ? 0xFF66FF99 : 0xFFFF4D4D);
                }
            }
        }

        // --- lists
        int listTop = top + 16 + labels.length * 12 + 10;
        int colWidth = Math.min(190, (width - 40) / 2);
        int lx = width / 2 - colWidth - 8, rx = width / 2 + 8;
        g.fill(lx, listTop, lx + colWidth, listTop + 14, 0xFF2A1A4A);
        g.drawString(font, "Top payers (received)", lx + 6, listTop + 3, 0xFFFFD166);
        g.fill(rx, listTop, rx + colWidth, listTop + 14, 0xFF2A1A4A);
        g.drawString(font, "Latest transactions", rx + 6, listTop + 3, 0xFFFFD166);

        int rows = Math.max(1, (height - listTop - 60) / 11);
        List<Map.Entry<String, Double>> tot = History.totals();
        for (int i = 0; i < Math.min(rows, tot.size()); i++) {
            int y = listTop + 18 + i * 11;
            g.drawString(font, (i + 1) + ". " + tot.get(i).getKey(), lx + 6, y, i == 0 ? 0xFF66FF99 : 0xFFFFFFFF);
            cell(g, PayFx.fmt(tot.get(i).getValue()), lx + colWidth - 6, y, 0xFFFFD166);
        }
        int n = History.LIST.size();
        for (int i = 0; i < Math.min(rows, n); i++) {
            History.Entry e = History.LIST.get(n - 1 - i);
            int y = listTop + 18 + i * 11;
            g.drawString(font, FMT.format(Instant.ofEpochMilli(e.ts())) + "  " + e.name(), rx + 6, y, 0xFFB8B8C8);
            cell(g, (e.income() ? "+" : "-") + PayFx.fmt(e.amount()), rx + colWidth - 6, y, e.income() ? 0xFF66FF99 : 0xFFFF6A6A);
        }
        if (n == 0) g.drawString(font, "Nothing recorded yet.", rx + 6, listTop + 18, 0xFF7A7A90);

        bx = width / 2 - 60;
        by = height - 30;
        boolean hov = mx >= bx && mx < bx + 120 && my >= by && my < by + 16;
        g.fill(bx, by, bx + 120, by + 16, hov ? 0xFF5A1E1E : 0xFF3A1414);
        String b = "Clear history";
        g.drawString(font, b, bx + (120 - font.width(b)) / 2, by + 4, 0xFFFF9A9A);
        String hint = "ESC: back   |   /payfx tax <percent>   /payfx expense <amount>   /payfx income <amount>";
        g.drawString(font, hint, (width - font.width(hint)) / 2, height - 11, 0xFF7A7A90);
    }

    private static String trim(double d) {
        return d == (long) d ? Long.toString((long) d) : Double.toString(d);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (event.button() == 0 && mx >= bx && mx < bx + 120 && my >= by && my < by + 16) {
            History.clear();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}

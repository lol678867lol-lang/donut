package de.payfx;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Statistics with tabs: Overview, Charts, Players, Auctions, Latest. */
public class HistoryScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] TABS = {"Overview", "Charts", "Players", "Auctions", "Latest"};
    private static int tab = 0; // remembered while the game runs

    private int bx, by, tabX, tabW, tw, tx;
    private int auBx, auBy;
    private String hover = "";

    public HistoryScreen() {
        super(Component.literal("PayFX Statistics"));
    }

    public HistoryScreen(int startTab) {
        this();
        tab = Math.max(0, Math.min(TABS.length - 1, startTab));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void right(GuiGraphics g, String s, int rightX, int y, int color) {
        g.drawString(font, s, rightX - font.width(s), y, color);
    }

    private static String trim(double d) {
        return d == (long) d ? Long.toString((long) d) : Double.toString(d);
    }

    // ---------------------------------------------------------------- frame

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xB0050510, 0xE0140A28);
        hover = "";
        String title = "P A Y F X   S T A T I S T I C S";
        g.drawString(font, title, (width - font.width(title)) / 2, 8, 0xFFFFD166);

        tw = Math.min(420, width - 20);
        tx = (width - tw) / 2;
        tabX = tx;
        tabW = tw / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int x = tabX + i * tabW;
            boolean sel = i == tab;
            boolean hov = mx >= x && mx < x + tabW && my >= 22 && my < 38;
            g.fill(x, 22, x + tabW - 2, 38, sel ? 0xFF5A3A9A : (hov ? 0xFF3A2A66 : 0xFF241846));
            if (sel) g.fill(x, 36, x + tabW - 2, 38, 0xFFFFD166);
            g.drawString(font, TABS[i], x + (tabW - 2 - font.width(TABS[i])) / 2, 26, sel ? 0xFFFFFFFF : 0xFFB8B8C8);
        }

        int top = 46;
        switch (tab) {
            case 0 -> overview(g, top, mx, my);
            case 1 -> charts(g, top, mx, my);
            case 2 -> players(g, top);
            case 3 -> auctions(g, top, mx, my);
            default -> latest(g, top);
        }
        if (!hover.isEmpty()) {
            g.fill(mx + 6, my - 14, mx + 12 + font.width(hover), my - 2, 0xE0000000);
            g.drawString(font, hover, mx + 9, my - 12, 0xFFFFFFFF);
        }

        bx = width / 2 - 60;
        by = height - 30;
        boolean hov = mx >= bx && mx < bx + 120 && my >= by && my < by + 16;
        g.fill(bx, by, bx + 120, by + 16, hov ? 0xFF5A1E1E : 0xFF3A1414);
        String b = "Clear history";
        g.drawString(font, b, bx + (120 - font.width(b)) / 2, by + 4, 0xFFFF9A9A);
        String hint = "ESC: back   |   /payfx tax <percent>   /payfx expense <amount>   /payfx export";
        g.drawString(font, hint, (width - font.width(hint)) / 2, height - 11, 0xFF7A7A90);
    }

    // ---------------------------------------------------------------- tab 0: overview

    private void overview(GuiGraphics g, int top, int mx, int my) {
        long[] since = {History.sessionStart, History.startOfToday(), System.currentTimeMillis() - 7L * 86_400_000L, 0L};
        String[] heads = {"Session", "Today", "7 days", "All time"};
        int colW = (tw - 110) / 4;
        g.fill(tx, top, tx + tw, top + 12, 0xFF2A1A4A);
        for (int c = 0; c < 4; c++) right(g, heads[c], tx + 110 + colW * (c + 1) - 4, top + 2, 0xFFFFD166);
        String[] labels = {"Income (received)", "Tax lost (" + trim(Cfg.taxPercent) + "%)", "Income after tax", "Expenses (sent)", "PROFIT / LOSS"};
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
                    case 0 -> right(g, PayFx.fmt(inc), rx, y, 0xFFFFFFFF);
                    case 1 -> right(g, "-" + PayFx.fmt(tax), rx, y, 0xFFFFAA44);
                    case 2 -> right(g, PayFx.fmt(inc - tax), rx, y, 0xFFFFFFFF);
                    case 3 -> right(g, "-" + PayFx.fmt(exp), rx, y, 0xFFFF6A6A);
                    default -> right(g, PayFx.fmtSigned(net), rx, y, net >= 0 ? 0xFF66FF99 : 0xFFFF4D4D);
                }
            }
        }
        // profit per day
        int chartTop = top + 16 + labels.length * 12 + 10;
        g.drawString(font, "Profit per day (after tax)", tx + 4, chartTop, 0xFFFFD166);
        int mid = chartTop + 34;
        double[] day = new double[7];
        double maxAbs = 1;
        for (int i = 0; i < 7; i++) {
            day[i] = History.netBetween(History.dayStart(6 - i), i == 6 ? Long.MAX_VALUE : History.dayStart(5 - i));
            maxAbs = Math.max(maxAbs, Math.abs(day[i]));
        }
        int barW = Math.min(44, (tw - 20) / 7 - 6);
        for (int i = 0; i < 7; i++) {
            int x = tx + 10 + i * (barW + 6);
            int h = (int) (Math.abs(day[i]) / maxAbs * 30);
            if (day[i] >= 0) g.fill(x, mid - h, x + barW, mid, 0xFF3DDC84);
            else g.fill(x, mid, x + barW, mid + h, 0xFFFF4D4D);
            dayLabel(g, i, x, barW, mid + 32);
            if (mx >= x && mx < x + barW && my >= chartTop && my < mid + 44) hover = PayFx.fmtSigned(day[i]);
        }
        g.fill(tx + 6, mid, tx + tw - 6, mid + 1, 0xFF3A3A55);
    }

    private void dayLabel(GuiGraphics g, int i, int x, int barW, int y) {
        String dl = LocalDate.now().minusDays(6 - i).format(DateTimeFormatter.ofPattern("dd.MM"));
        g.drawString(font, dl, x + (barW - font.width(dl)) / 2, y, 0xFF7A7A90);
    }

    // ---------------------------------------------------------------- tab 1: income vs expenses, tax

    private void charts(GuiGraphics g, int top, int mx, int my) {
        double[] inc = new double[7], exp = new double[7], tax = new double[7];
        double max = 1, maxTax = 1;
        for (int i = 0; i < 7; i++) {
            long from = History.dayStart(6 - i), to = i == 6 ? Long.MAX_VALUE : History.dayStart(5 - i);
            inc[i] = History.sumBetween(true, from, to);
            exp[i] = History.sumBetween(false, from, to);
            tax[i] = inc[i] * Cfg.taxPercent / 100.0;
            max = Math.max(max, Math.max(inc[i], exp[i]));
            maxTax = Math.max(maxTax, tax[i]);
        }
        int half = Math.max(20, (height - top - 60) / 2 - 18);
        g.drawString(font, "Income (green) vs. expenses (red) per day", tx + 4, top, 0xFFFFD166);
        int base = top + 12 + half;
        int barW = Math.min(20, (tw - 20) / 14 - 3);
        for (int i = 0; i < 7; i++) {
            int x = tx + 10 + i * (barW * 2 + 12);
            int h1 = (int) (inc[i] / max * half), h2 = (int) (exp[i] / max * half);
            g.fill(x, base - h1, x + barW, base, 0xFF3DDC84);
            g.fill(x + barW + 2, base - h2, x + barW * 2 + 2, base, 0xFFFF4D4D);
            dayLabel(g, i, x, barW * 2 + 2, base + 3);
            if (mx >= x && mx < x + barW * 2 + 2 && my >= top && my < base + 14) hover = "in " + PayFx.fmt(inc[i]) + "  out " + PayFx.fmt(exp[i]);
        }
        g.fill(tx + 6, base, tx + tw - 6, base + 1, 0xFF3A3A55);

        int top2 = base + 18;
        g.drawString(font, "Tax lost per day (" + trim(Cfg.taxPercent) + "%)", tx + 4, top2, 0xFFFFD166);
        int base2 = top2 + 12 + half;
        int bw2 = Math.min(44, (tw - 20) / 7 - 6);
        double totalTax = 0;
        for (int i = 0; i < 7; i++) {
            int x = tx + 10 + i * (bw2 + 6);
            int h = (int) (tax[i] / maxTax * half);
            g.fill(x, base2 - h, x + bw2, base2, 0xFFFFAA44);
            dayLabel(g, i, x, bw2, base2 + 3);
            totalTax += tax[i];
            if (mx >= x && mx < x + bw2 && my >= top2 && my < base2 + 14) hover = "tax " + PayFx.fmt(tax[i]);
        }
        g.fill(tx + 6, base2, tx + tw - 6, base2 + 1, 0xFF3A3A55);
        right(g, "7 days: -" + PayFx.fmt(totalTax), tx + tw - 4, top2, 0xFFFFAA44);
    }

    // ---------------------------------------------------------------- tab 2: players

    private void players(GuiGraphics g, int top) {
        g.drawString(font, "Top payers (received money)", tx + 4, top, 0xFFFFD166);
        List<Map.Entry<String, Double>> tot = History.totals();
        Map<String, Integer> counts = History.counts();
        if (tot.isEmpty()) {
            g.drawString(font, "Nothing recorded yet.", tx + 4, top + 16, 0xFF7A7A90);
            return;
        }
        int rows = Math.min(tot.size(), Math.max(1, (height - top - 50) / 16));
        double max = tot.get(0).getValue();
        int labelW = 100, barMax = tw - labelW - 90;
        for (int i = 0; i < rows; i++) {
            int y = top + 16 + i * 16;
            String name = (i + 1) + ". " + tot.get(i).getKey();
            g.drawString(font, name, tx + 4, y + 2, i == 0 ? 0xFF66FF99 : 0xFFFFFFFF);
            int w = (int) (tot.get(i).getValue() / max * barMax);
            g.fill(tx + labelW, y, tx + labelW + Math.max(2, w), y + 11, i == 0 ? 0xFF3DDC84 : 0xFF7A5CFF);
            String s = PayFx.fmt(tot.get(i).getValue()) + " (x" + counts.getOrDefault(tot.get(i).getKey(), 0) + ")";
            g.drawString(font, s, tx + labelW + Math.max(2, w) + 4, y + 2, 0xFFFFD166);
        }
    }

    // ---------------------------------------------------------------- tab 3: auctions

    private void auctions(GuiGraphics g, int top, int mx, int my) {
        List<AuctionLog.Rec> all = AuctionLog.LIST;
        double worth = 0, got = 0;
        int sold = 0;
        for (AuctionLog.Rec r : all) {
            worth += r.worth();
            got += r.received();
            if (r.received() > 0) sold++;
        }
        String sum = all.size() + " auctions (" + sold + " sold)  Worth " + PayFx.fmt(worth) + "  Got " + PayFx.fmt(got)
                + "  Result " + PayFx.fmtSigned(got - worth);
        g.drawString(font, sum, tx + 4, top, got - worth >= 0 ? 0xFF66FF99 : 0xFFFF6A6A);
        g.drawString(font, "orange = worth of the item, gold = what you got", tx + 4, top + 12, 0xFF7A7A90);
        int rows = Math.min(all.size(), Math.max(1, (height - top - 96) / 18));
        double max = 1;
        for (int i = 0; i < rows; i++) {
            AuctionLog.Rec r = all.get(all.size() - 1 - i);
            max = Math.max(max, Math.max(r.worth(), r.received()));
        }
        int labelW = 110, barMax = tw - labelW - 70;
        for (int i = 0; i < rows; i++) {
            AuctionLog.Rec r = all.get(all.size() - 1 - i);
            int y = top + 28 + i * 18;
            String name = r.item();
            while (name.length() > 3 && font.width(name) > labelW - 6) name = name.substring(0, name.length() - 1);
            g.drawString(font, name, tx + 4, y + 3, 0xFFFFFFFF);
            int w1 = (int) (r.worth() / max * barMax), w2 = (int) (r.received() / max * barMax);
            g.fill(tx + labelW, y, tx + labelW + Math.max(1, w1), y + 7, 0xFFFFAA44);
            g.fill(tx + labelW, y + 8, tx + labelW + Math.max(1, w2), y + 15, 0xFFFFD166);
            double p = r.received() - r.worth();
            right(g, r.received() > 0 ? PayFx.fmtSigned(p) : "no sale", tx + tw - 4, y + 3, r.received() <= 0 ? 0xFF7A7A90 : (p >= 0 ? 0xFF66FF99 : 0xFFFF6A6A));
            if (mx >= tx && mx < tx + tw && my >= y && my < y + 16) hover = r.item() + " - " + (r.received() > 0 ? r.winner() : "no sale");
        }
        if (all.isEmpty()) g.drawString(font, "No auctions yet. Start one with /auction start.", tx + 4, top + 28, 0xFF7A7A90);
        auBx = tx;
        auBy = height - 52;
        boolean hov = mx >= auBx && mx < auBx + 150 && my >= auBy && my < auBy + 16;
        g.fill(auBx, auBy, auBx + 150, auBy + 16, hov ? 0xFF3A2A66 : 0xFF241846);
        String t = "Open auction list (search)";
        g.drawString(font, t, auBx + (150 - font.width(t)) / 2, auBy + 4, 0xFFFFFFFF);
    }

    // ---------------------------------------------------------------- tab 4: latest

    private void latest(GuiGraphics g, int top) {
        g.drawString(font, "Latest transactions", tx + 4, top, 0xFFFFD166);
        int n = History.LIST.size();
        int rows = Math.max(1, (height - top - 50) / 11);
        for (int i = 0; i < Math.min(rows, n); i++) {
            History.Entry e = History.LIST.get(n - 1 - i);
            int y = top + 16 + i * 11;
            g.drawString(font, FMT.format(Instant.ofEpochMilli(e.ts())) + "  " + e.name(), tx + 4, y, 0xFFB8B8C8);
            right(g, (e.income() ? "+" : "-") + PayFx.fmt(e.amount()), tx + tw - 4, y, e.income() ? 0xFF66FF99 : 0xFFFF6A6A);
        }
        if (n == 0) g.drawString(font, "Nothing recorded yet.", tx + 4, top + 16, 0xFF7A7A90);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (event.button() == 0) {
            if (my >= 22 && my < 38 && mx >= tabX && mx < tabX + tabW * TABS.length) {
                tab = (int) ((mx - tabX) / tabW);
                return true;
            }
            if (mx >= bx && mx < bx + 120 && my >= by && my < by + 16) {
                History.clear();
                return true;
            }
            if (tab == 3 && mx >= auBx && mx < auBx + 150 && my >= auBy && my < auBy + 16) {
                net.minecraft.client.Minecraft.getInstance().setScreen(new AuctionScreen());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
}

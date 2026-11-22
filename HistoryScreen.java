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

/** Zahlungs-Verlauf: Summe pro Spieler und die letzten Zahlungen. */
public class HistoryScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private int bx, by;

    public HistoryScreen() {
        super(Component.literal("PayFX Verlauf"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xB0050510, 0xE0140A28);
        String title = "Z A H L U N G S - V E R L A U F";
        g.drawString(font, title, (width - font.width(title)) / 2, 14, 0xFFFFD166);
        String total = "Gesamt erhalten: " + PayFx.fmt(History.sum()) + "   (" + History.LIST.size() + " Zahlungen)";
        g.drawString(font, total, (width - font.width(total)) / 2, 28, 0xFFB8B8C8);

        int colW = Math.min(190, (width - 40) / 2);
        int lx = width / 2 - colW - 8, rx = width / 2 + 8, top = 48;
        g.fill(lx, top, lx + colW, top + 14, 0xFF2A1A4A);
        g.drawString(font, "Top-Spieler (Summe)", lx + 6, top + 3, 0xFFFFD166);
        g.fill(rx, top, rx + colW, top + 14, 0xFF2A1A4A);
        g.drawString(font, "Letzte Zahlungen", rx + 6, top + 3, 0xFFFFD166);

        int rows = Math.max(1, (height - top - 60) / 11);
        List<Map.Entry<String, Double>> tot = History.totals();
        for (int i = 0; i < Math.min(rows, tot.size()); i++) {
            String s = (i + 1) + ". " + tot.get(i).getKey();
            String v = PayFx.fmt(tot.get(i).getValue());
            int y = top + 18 + i * 11;
            g.drawString(font, s, lx + 6, y, i == 0 ? 0xFF66FF99 : 0xFFFFFFFF);
            g.drawString(font, v, lx + colW - 6 - font.width(v), y, 0xFFFFD166);
        }
        int n = History.LIST.size();
        for (int i = 0; i < Math.min(rows, n); i++) {
            History.Entry e = History.LIST.get(n - 1 - i);
            int y = top + 18 + i * 11;
            g.drawString(font, FMT.format(Instant.ofEpochMilli(e.ts())) + "  " + e.name(), rx + 6, y, 0xFFB8B8C8);
            String v = PayFx.fmt(e.amount());
            g.drawString(font, v, rx + colW - 6 - font.width(v), y, 0xFFFFD166);
        }
        if (n == 0) g.drawString(font, "Noch keine Zahlungen erkannt.", rx + 6, top + 18, 0xFF7A7A90);

        bx = width / 2 - 60;
        by = height - 30;
        boolean hov = mx >= bx && mx < bx + 120 && my >= by && my < by + 16;
        g.fill(bx, by, bx + 120, by + 16, hov ? 0xFF5A1E1E : 0xFF3A1414);
        String b = "Verlauf loeschen";
        g.drawString(font, b, bx + (120 - font.width(b)) / 2, by + 4, 0xFFFF9A9A);
        String hint = "ESC: zurueck";
        g.drawString(font, hint, (width - font.width(hint)) / 2, height - 11, 0xFF7A7A90);
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

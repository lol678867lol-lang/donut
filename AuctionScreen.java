package de.payfx;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Auction history with search: what was auctioned, what it was worth, what you got. */
public class AuctionScreen extends Screen {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private EditBox search;
    private double scroll;
    private int bx, by;

    public AuctionScreen() {
        super(Component.literal("PayFX Auctions"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        int w = Math.min(220, width - 20);
        search = new EditBox(font, (width - w) / 2, 24, w, 14, Component.literal("Search"));
        search.setMaxLength(40);
        search.setResponder(v -> scroll = 0);
        addWidget(search);
    }

    private void right(GuiGraphics g, String s, int rx, int y, int color) {
        g.drawString(font, s, rx - font.width(s), y, color);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xB0050510, 0xE0140A28);
        String title = "P A Y F X   A U C T I O N S";
        g.drawString(font, title, (width - font.width(title)) / 2, 8, 0xFFFFD166);
        search.render(g, mx, my, delta);
        if (search.getValue().isEmpty() && !search.isFocused()) {
            g.drawString(font, "Search item or winner ...", search.getX() + 4, search.getY() + 3, 0xFF6A6A80);
        }

        List<AuctionLog.Rec> list = AuctionLog.search(search.getValue());
        double worth = 0, got = 0;
        int sold = 0;
        for (AuctionLog.Rec r : list) {
            worth += r.worth();
            got += r.received();
            if (r.received() > 0) sold++;
        }
        int tw = Math.min(420, width - 20);
        int tx = (width - tw) / 2;
        String sum = list.size() + " auctions (" + sold + " sold)   Worth: " + PayFx.fmt(worth)
                + "   Received: " + PayFx.fmt(got) + "   Result: " + PayFx.fmtSigned(got - worth);
        g.drawString(font, sum, (width - font.width(sum)) / 2, 44, got - worth >= 0 ? 0xFF66FF99 : 0xFFFF6A6A);

        int top = 58;
        g.fill(tx, top, tx + tw, top + 12, 0xFF2A1A4A);
        g.drawString(font, "Date / item", tx + 4, top + 2, 0xFFFFD166);
        right(g, "Winner", tx + tw - 190, top + 2, 0xFFFFD166);
        right(g, "Worth", tx + tw - 110, top + 2, 0xFFFFD166);
        right(g, "Got", tx + tw - 55, top + 2, 0xFFFFD166);
        right(g, "+/-", tx + tw - 4, top + 2, 0xFFFFD166);

        int rowH = 11;
        int areaTop = top + 14, areaBottom = height - 36;
        int visible = Math.max(1, (areaBottom - areaTop) / rowH);
        int maxScroll = Math.max(0, list.size() - visible);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        int first = (int) scroll;
        for (int i = 0; i < visible && first + i < list.size(); i++) {
            AuctionLog.Rec r = list.get(first + i);
            int y = areaTop + i * rowH;
            String name = FMT.format(Instant.ofEpochMilli(r.ts())) + "  " + r.item();
            while (name.length() > 4 && font.width(name) > tw - 215) name = name.substring(0, name.length() - 1);
            g.drawString(font, name, tx + 4, y, 0xFFFFFFFF);
            right(g, r.received() > 0 ? r.winner() : "no sale", tx + tw - 190, y, r.received() > 0 ? 0xFFB8B8C8 : 0xFF7A7A90);
            right(g, PayFx.fmt(r.worth()), tx + tw - 110, y, 0xFFFFAA44);
            right(g, PayFx.fmt(r.received()), tx + tw - 55, y, 0xFFFFD166);
            double p = r.profit();
            right(g, r.received() > 0 ? PayFx.fmtSigned(p) : "-", tx + tw - 4, y, p >= 0 ? 0xFF66FF99 : 0xFFFF6A6A);
        }
        if (list.isEmpty()) g.drawString(font, "Nothing found.", tx + 4, areaTop, 0xFF7A7A90);

        bx = width / 2 - 60;
        by = height - 30;
        boolean hov = mx >= bx && mx < bx + 120 && my >= by && my < by + 16;
        g.fill(bx, by, bx + 120, by + 16, hov ? 0xFF5A1E1E : 0xFF3A1414);
        String b = "Clear auction log";
        g.drawString(font, b, bx + (120 - font.width(b)) / 2, by + 4, 0xFFFF9A9A);
        String hint = "Mouse wheel: scroll   ESC: back   |   /auction value <amount> sets the worth of the current item";
        g.drawString(font, hint, (width - font.width(hint)) / 2, height - 11, 0xFF7A7A90);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= scrollY * 2;
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (event.button() == 0 && mx >= bx && mx < bx + 120 && my >= by && my < by + 16) {
            AuctionLog.clear();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}

package de.payfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * T key (chat) while looking at a block: the chat opens with "/ah <block>" or "/order <block>".
 * With "only with mouse wheel pressed" on: press the mouse wheel first (held, or pressed within the last 3 seconds), then T.
 */
final class Shortcut {
    private Shortcut() {}

    private static Screen last;
    private static long middleAt;

    private static boolean middleDown(Minecraft mc) {
        long win = GLFW.glfwGetCurrentContext();
        return GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS || mc.options.keyPickItem.isDown();
    }

    /** Called at the start of every client tick: remembers when the mouse wheel was pressed. */
    static void poll(Minecraft mc) {
        if (middleDown(mc)) middleAt = System.currentTimeMillis();
    }

    static void onScreen(Minecraft mc, Screen screen) {
        if (!(screen instanceof ChatScreen)) {
            last = null;
            return;
        }
        if (screen == last) return;
        last = screen;
        if (!Cfg.tShortcut || mc.level == null) return;

        // Only when the chat was opened empty (T). Opening it with "/" already has text in it.
        EditBox input = null;
        for (GuiEventListener c : screen.children()) {
            if (c instanceof EditBox eb) {
                input = eb;
                break;
            }
        }
        if (input == null || !input.getValue().isEmpty()) return;

        if (Cfg.needMiddle) {
            boolean recent = System.currentTimeMillis() - middleAt < 3000;
            if (!recent && !middleDown(mc)) return;
        }

        String name = "";
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            name = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bhr.getBlockPos()).getBlock()).getPath().replace('_', ' ');
        } else if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof ItemEntity ie) {
            name = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).getPath().replace('_', ' ');
        }
        input.setValue("/" + Cfg.shortMode + " " + name);
    }
}

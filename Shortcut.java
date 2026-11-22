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

/** Taste T (Chat) mit Block im Fadenkreuz: der Chat oeffnet mit "/ah <block>" bzw. "/order <block>". */
final class Shortcut {
    private Shortcut() {}

    private static Screen last;

    static void onScreen(Minecraft mc, Screen screen) {
        if (!(screen instanceof ChatScreen)) {
            last = null;
            return;
        }
        if (screen == last) return;
        last = screen;
        if (!Cfg.tShortcut || mc.level == null || !mc.options.keyChat.isDown()) return;
        if (Cfg.needMiddle) {
            long win = GLFW.glfwGetCurrentContext();
            if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) != GLFW.GLFW_PRESS) return;
        }
        String name = "";
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            name = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bhr.getBlockPos()).getBlock()).getPath().replace('_', ' ');
        } else if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof ItemEntity ie) {
            name = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).getPath().replace('_', ' ');
        }
        // Das Eingabefeld des Chats ist ein oeffentliches Kind-Element des Fensters
        for (GuiEventListener c : screen.children()) {
            if (c instanceof EditBox eb) {
                eb.setValue("/" + Cfg.shortMode + " " + name);
                break;
            }
        }
    }
}

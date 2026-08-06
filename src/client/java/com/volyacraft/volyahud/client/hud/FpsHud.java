package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Невеликий інформаційний блок у верхньому правому куті: FPS, координати
 * гравця та координати чанка, в якому він зараз стоїть. Кожен рядок
 * вмикається окремим тумблером у меню налаштувань HUD.
 */
public class FpsHud {

	public static void render(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		int y = 4;

		if (cfg.showFps) {
			int fps = mc.getFps();
			int color = fps >= 100 ? 0x55FF55 : fps >= 40 ? 0xFFFF55 : 0xFF5555;
			y = drawLine(graphics, mc, fps + " FPS", color, y);
		}

		if (mc.player != null && cfg.showCoords) {
			String text = String.format("X: %.1f  Y: %.1f  Z: %.1f",
					mc.player.getX(), mc.player.getY(), mc.player.getZ());
			y = drawLine(graphics, mc, text, 0xFFFFFF, y);
		}

		if (mc.player != null && cfg.showChunkCoords) {
			int blockX = Mth.floor(mc.player.getX());
			int blockZ = Mth.floor(mc.player.getZ());
			int chunkX = blockX >> 4;
			int chunkZ = blockZ >> 4;
			int localX = blockX & 15;
			int localZ = blockZ & 15;
			String text = "Чанк: " + chunkX + " / " + chunkZ + "  (у чанку " + localX + "," + localZ + ")";
			drawLine(graphics, mc, text, 0xAAAAFF, y);
		}
	}

	private static int drawLine(GuiGraphics graphics, Minecraft mc, String text, int color, int y) {
		int textWidth = mc.font.width(text);
		int x = graphics.guiWidth() - textWidth - 6;
		graphics.fill(x - 3, y - 2, x + textWidth + 3, y + 9, 0x60000000);
		graphics.drawString(mc.font, text, x, y, color, true);
		return y + 11;
	}
}

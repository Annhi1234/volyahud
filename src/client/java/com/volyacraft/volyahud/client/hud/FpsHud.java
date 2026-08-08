package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Невеликий інформаційний блок: FPS, координати гравця (X/Y/Z можна
 * вмикати/вимикати окремо) та координати чанка. Кут екрана, де малюється
 * панель, тепер налаштовується (як і в SodiumOptions / інших HUD-модів):
 * зверху-справа, зверху-зліва, знизу-справа або знизу-зліва.
 */
public class FpsHud {

	public static void render(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;

		java.util.List<Line> lines = new java.util.ArrayList<>();

		if (cfg.showFps) {
			int fps = mc.getFps();
			int color = fps >= 100 ? 0x55FF55 : fps >= 40 ? 0xFFFF55 : 0xFF5555;
			lines.add(new Line(fps + " FPS", color));
		}

		if (mc.player != null && (cfg.showCoordX || cfg.showCoordY || cfg.showCoordZ)) {
			StringBuilder sb = new StringBuilder();
			if (cfg.showCoordX) sb.append(String.format("X: %.1f  ", mc.player.getX()));
			if (cfg.showCoordY) sb.append(String.format("Y: %.1f  ", mc.player.getY()));
			if (cfg.showCoordZ) sb.append(String.format("Z: %.1f", mc.player.getZ()));
			lines.add(new Line(sb.toString().stripTrailing(), 0xFFFFFF));
		}

		if (mc.player != null && cfg.showChunkCoords) {
			int blockX = Mth.floor(mc.player.getX());
			int blockZ = Mth.floor(mc.player.getZ());
			int chunkX = blockX >> 4;
			int chunkZ = blockZ >> 4;
			int localX = blockX & 15;
			int localZ = blockZ & 15;
			lines.add(new Line("Чанк: " + chunkX + " / " + chunkZ + "  (у чанку " + localX + "," + localZ + ")", 0xAAAAFF));
		}

		// Компас: напрям погляду. Дрібниця, але корисна в PvP (наприклад
		// щоб домовитись "рухайся на схід" по чату) і без неї в HUD взагалі
		// не було жодного способу дізнатись сторону світу без F3.
		if (mc.player != null && cfg.showCompass) {
			lines.add(new Line(compassText(mc.player.getYRot()), 0xFFD780));
		}

		if (lines.isEmpty()) return;

		boolean right = cfg.infoPanelCorner == HudConfig.ScreenCorner.TOP_RIGHT
				|| cfg.infoPanelCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;
		boolean bottom = cfg.infoPanelCorner == HudConfig.ScreenCorner.BOTTOM_LEFT
				|| cfg.infoPanelCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		int lineHeight = 11;
		int totalH = lines.size() * lineHeight;
		int panelW = 0;
		for (Line line : lines) {
			panelW = Math.max(panelW, mc.font.width(line.text()));
		}
		panelW += 8;

		int y = bottom ? (screenH - 6 - totalH) : 4;
		int x = right ? (screenW - panelW - 4) : 4;

		// Ручне зміщення, як і у всіх інших панелях (броня/мінімапа/бафи).
		x = HudStyle.clampX(x + cfg.infoPanelOffsetX, panelW, screenW);
		y = HudStyle.clampY(y + cfg.infoPanelOffsetY, totalH, screenH);

		HudStyle.panel(graphics, x - 2, y - 3, x + panelW - 2, y + totalH + 1);

		for (Line line : lines) {
			y = drawLine(graphics, mc, line.text(), line.color(), x, y, right, panelW);
		}
	}

	private record Line(String text, int color) {
	}

	// Порядок відповідає тому, як зростає ванільний yaw (0=Пд, 90=Зх, 180=Пн, 270/-90=Сх).
	private static final String[] DIRECTIONS = {
			"\u041F\u0434 (S)", "\u041F\u0434-\u0417\u0445", "\u0417\u0445 (W)", "\u041F\u043D-\u0417\u0445",
			"\u041F\u043D (N)", "\u041F\u043D-\u0421\u0445", "\u0421\u0445 (E)", "\u041F\u0434-\u0421\u0445"
	};

	/** Ванільний yaw: 0 = південь, 90 = захід, 180/-180 = північ, -90 = схід. */
	private static String compassText(float yaw) {
		float normalized = Mth.wrapDegrees(yaw);
		int index = Math.floorMod(Math.round(normalized / 45f), 8);
		int degrees = Math.round(normalized);
		if (degrees < 0) degrees += 360;
		return DIRECTIONS[index] + "  " + degrees + "\u00B0";
	}

	private static int drawLine(GuiGraphics graphics, Minecraft mc, String text, int color, int panelX, int y, boolean right, int panelW) {
		int textWidth = mc.font.width(text);
		int textX = right ? (panelX + panelW - textWidth - 4) : (panelX + 2);
		graphics.drawString(mc.font, text, textX, y, color, true);
		return y + 11;
	}
}

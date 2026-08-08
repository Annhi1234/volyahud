package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Спільний "гарний" стиль панелей для ВСІХ HUD-елементів: напівпрозорий
 * чорний фон з легкою рамкою і тонкою світлою лінією зверху (той самий
 * стиль, що й у панелі броні). Раніше кожен HUD-елемент малював свій фон
 * трохи по-своєму (десь просто суцільна заливка, десь взагалі без рамки) —
 * тепер усі панелі виглядають однаково акуратно.
 */
public final class HudStyle {

	private HudStyle() {
	}

	public static final int PANEL_BG = 0x80000000;
	public static final int PANEL_BORDER = 0x40FFFFFF;
	public static final int PANEL_TOP_HIGHLIGHT = 0x55FFFFFF;

	/** Фон панелі: заливка + тонка рамка + світла лінія зверху. */
	public static void panel(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
		graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, PANEL_BORDER);
		graphics.fill(x1, y1, x2, y2, PANEL_BG);
		graphics.fill(x1, y1, x2, y1 + 1, PANEL_TOP_HIGHLIGHT);
	}

	/**
	 * Утримує координати панелі в межах екрана, щоб зсув (offsetX/Y) у
	 * налаштуваннях не міг випхнути її повністю за край і "загубити".
	 */
	public static int clampX(int x, int panelW, int screenW) {
		return Math.max(-panelW + 8, Math.min(x, screenW - 8));
	}

	public static int clampY(int y, int panelH, int screenH) {
		return Math.max(0, Math.min(y, screenH - 8));
	}
}

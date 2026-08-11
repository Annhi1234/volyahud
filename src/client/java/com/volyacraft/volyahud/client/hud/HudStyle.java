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

	// Кольори "утопленого" слота, як у стандартному інвентарі Minecraft:
	// сіра заливка + темна зовнішня рамка + світла фаска зверху-зліва
	// і темна фаска знизу-справа (той самий ефект, що дає vanilla-текстура
	// слота, тільки намальований заливками, щоб не тягнути окрему текстуру).
	private static final int SLOT_BORDER = 0xF0373737;
	private static final int SLOT_FILL = 0xC08B8B8B;
	private static final int SLOT_BEVEL_LIGHT = 0x45FFFFFF;
	private static final int SLOT_BEVEL_DARK = 0x70000000;

	/**
	 * Малює один інвентарний слот у стилі ваніли: квадрат {@code size}x{@code size}
	 * з темною рамкою по краю, сірою "утопленою" заливкою всередині і легкою
	 * фаскою (світліше зверху й зліва, темніше знизу і справа), щоб слот
	 * виглядав об'ємно, як у справжньому GUI інвентарю.
	 */
	public static void mcSlot(GuiGraphics graphics, int x, int y, int size) {
		graphics.fill(x, y, x + size, y + size, SLOT_BORDER);
		graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_FILL);
		graphics.fill(x + 1, y + 1, x + size - 1, y + 2, SLOT_BEVEL_LIGHT);
		graphics.fill(x + 1, y + 1, x + 2, y + size - 1, SLOT_BEVEL_LIGHT);
		graphics.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, SLOT_BEVEL_DARK);
		graphics.fill(x + size - 2, y + 1, x + size - 1, y + size - 1, SLOT_BEVEL_DARK);
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

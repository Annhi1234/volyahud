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

	/**
	 * Кольори панелей — "темна" тема (на прохання: щоб панелі броні та
	 * координат не світилися напівпрозорим сірим, а виглядали як суцільна
	 * темна підкладка — так контраст тексту й іконок вищий).
	 *
	 * <p>Значення — це ARGB у прямому порядку (0xAARRGGBB), тож:
	 * альфа = непрозорочність, далі RGB колір.
	 */
	public static final int PANEL_BG = 0xC0000000;        // майже чорний, 75% непрозорості
	public static final int PANEL_BORDER = 0x30000000;   // темна рамка
	public static final int PANEL_TOP_HIGHLIGHT = 0x25FFFFFF; // ледь помітна світла лінія зверху

	/**
	 * Фон панелі: заливка + тонка рамка + ледь помітна світла лінія зверху.
	 */
	public static void panel(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
		graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, PANEL_BORDER);
		graphics.fill(x1, y1, x2, y2, PANEL_BG);
		graphics.fill(x1, y1, x2, y1 + 1, PANEL_TOP_HIGHLIGHT);
	}

	// Кольори "утопленого" слота, як у стандартному інвентарі Minecraft:
	// сіра заливка + темна зовнішня рамка + світла фаска зверху-зліва
	// і темна фаска знизу-справа (той самий ефект, що дає vanilla-текстура
	// слота, тільки намальований заливками, щоб не тягнути окрему текстуру).
	private static final int SLOT_BORDER = 0xF0101010;
	private static final int SLOT_FILL = 0xD0353535;
	private static final int SLOT_BEVEL_LIGHT = 0x30FFFFFF;
	private static final int SLOT_BEVEL_DARK = 0x80000000;

	/**
	 * Малює один інвентарний слот у стилі ваніли: квадрат {@code size}x{@code size}
	 * з темною рамкою по краю, темною "утопленою" заливкою всередині і легкою
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
	 * Тільки рамка слота (без заливки всередині) — малюється <b>поверх</b>
	 * 3D-моделі предмета.
	 *
	 * <p>Навіщо окремо: `GuiGraphics#renderItem` у 1.21+ пише глибину в
	 * depth-буфер (3D-модель брони). Усе, що намальовано <b>перед</b> нею з
	 * Z = 0, опиняється <b>всередині</b> моделі й не видно. Тому рамку
	 * (і текст поверх неї) треба малювати після `renderItem` і на Z = 200 —
	 * саме так ванільний лічильник предметів висуває цифру перед моделлю.
	 */
	public static void mcSlotFrame(GuiGraphics graphics, int x, int y, int size) {
		graphics.pose().pushPose();
		graphics.pose().translate(x, y, 200.0f);
		// зовнішня темна рамка
		graphics.fill(0, 0, size, 1, SLOT_BORDER);
		graphics.fill(0, size - 1, size, size, SLOT_BORDER);
		graphics.fill(0, 0, 1, size, SLOT_BORDER);
		graphics.fill(size - 1, 0, size, size, SLOT_BORDER);
		// світла фаска зверху-зліва (підкреслює об'єм)
		graphics.fill(1, 1, size - 1, 2, SLOT_BEVEL_LIGHT);
		graphics.fill(1, 1, 2, size - 1, SLOT_BEVEL_LIGHT);
		// темна фаска знизу-справа
		graphics.fill(1, size - 2, size - 1, size - 1, SLOT_BEVEL_DARK);
		graphics.fill(size - 2, 1, size - 1, size - 1, SLOT_BEVEL_DARK);
		graphics.pose().popPose();
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

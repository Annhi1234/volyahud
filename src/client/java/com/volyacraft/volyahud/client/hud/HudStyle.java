package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Спільний "гарний" стиль панелей для ВСІХ HUD-елементів.
 *
 * <p>Вигляд: темна напівпрозора підкладка з м'яким вертикальним градієнтом,
 * тонкою рамкою, світлою лінією зверху й невеликим акцентом збоку. Раніше
 * кожен HUD-елемент малював фон трохи по-своєму (десь суцільна заливка,
 * десь взагалі без рамки) — тепер усі панелі виглядають як одна сім'я.
 */
public final class HudStyle {

	private HudStyle() {
	}

	/**
	 * Кольори панелей — темно-сіра тема. Значення — ARGB у прямому порядку
	 * (0xAARRGGBB): альфа, далі RGB.
	 *
	 * <p>Фон — виразний темно-сірий, а не чистий чорний: так панель
	 * відділяється від гри, не виглядає "діркою" й зберігає контраст тексту.
	 * Висока альфа не дає панелі «просвічувати» на темних сценах.
	 */
	public static final int PANEL_BG_TOP = 0xF03A3D43;
	public static final int PANEL_BG_BOTTOM = 0xF025272C;
	public static final int PANEL_BG = PANEL_BG_BOTTOM; // сумісність зі старими викликами
	public static final int PANEL_BORDER = 0xE0151619;
	public static final int PANEL_TOP_HIGHLIGHT = 0x40FFFFFF;
	public static final int PANEL_BOTTOM_SHADOW = 0x50000000;

	/** Акценти для різних типів інформації. */
	public static final int ACCENT_CYAN = 0xFF66D9FF;
	public static final int ACCENT_GOLD = 0xFFFFD166;
	public static final int ACCENT_GREEN = 0xFF63E06B;
	public static final int ACCENT_RED = 0xFFFF5A5A;
	public static final int TEXT_MUTED = 0xFFA6ADB8;

	/**
	 * Фон панелі: м'яка тінь, рамка, вертикальний градієнт, світла лінія
	 * зверху й темна — знизу.
	 */
	public static void panel(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
		panel(graphics, x1, y1, x2, y2, 0);
	}

	/**
	 * Сумісність зі старими викликами. Акцентну смужку зліва прибрано, тому
	 * {@code accent} більше не впливає на вигляд — панель малюється як звичайна.
	 */
	public static void panelAccent(GuiGraphics graphics, int x1, int y1, int x2, int y2, int accent) {
		panel(graphics, x1, y1, x2, y2, accent);
	}

	/** Панель з акцентом і плавною альфою (поява/зникнення сповіщень). */
	public static void panelAccent(GuiGraphics graphics, int x1, int y1, int x2, int y2, int accent,
			float alphaScale) {
		panel(graphics, x1, y1, x2, y2, accent, alphaScale);
	}

	/**
	 * Панель із акцентом і додатковою альфою (для плавної появи/зникнення
	 * сповіщень). {@code alphaScale} — 0..1.
	 */
	public static void panel(GuiGraphics graphics, int x1, int y1, int x2, int y2, int accent, float alphaScale) {
		if (x2 <= x1 || y2 <= y1) return;
		float a = Math.max(0f, Math.min(1f, alphaScale));
		if (a <= 0.01f) return;

		graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1,
				withAlpha(PANEL_BORDER, a));
		graphics.fillGradient(x1, y1, x2, y2,
				withAlpha(PANEL_BG_TOP, a), withAlpha(PANEL_BG_BOTTOM, a));
		graphics.fill(x1, y1, x2, y1 + 1, withAlpha(PANEL_TOP_HIGHLIGHT, a));
		graphics.fill(x1, y2 - 1, x2, y2, withAlpha(PANEL_BOTTOM_SHADOW, a));

		// Акцентну смужку зліва прибрано: вона виглядала як стороння
		// синя/кольорова полоска на темно-сірих панелях. Параметр accent
		// лишено для сумісності з викликами, але він більше нічого не малює.
	}

	private static void panel(GuiGraphics graphics, int x1, int y1, int x2, int y2, int accent) {
		panel(graphics, x1, y1, x2, y2, accent, 1f);
	}

	/** Тонка горизонтальна лінія-розділювач. */
	public static void divider(GuiGraphics graphics, int x1, int y, int x2) {
		if (x2 <= x1) return;
		graphics.fill(x1, y, x2, y + 1, 0x35FFFFFF);
	}

	/** Заголовок секції: текст із тінню та лінія під ним. */
	public static int header(GuiGraphics graphics, Font font, String text, int x, int y, int width, int accent) {
		// Коротку акцентну риску зліва теж прибрано — вона давала ту саму
		// синю «полоску», що й panelAccent. Текст починається одразу з x.
		graphics.drawString(font, text, x, y, 0xFFF4F6F8, true);
		divider(graphics, x, y + 11, x + width);
		return y + 15;
	}

	/** Малий "бейдж" під текст (наприклад, літера N на мінімапі). */
	public static void badge(GuiGraphics graphics, int x1, int y1, int x2, int y2, int accent) {
		if (x2 <= x1 || y2 <= y1) return;
		graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xE0151619);
		graphics.fill(x1, y1, x2, y2, 0xF036393F);
		// accent лишається в сигнатурі для сумісності, але верхня смужка
		// тепер нейтральна: кольорові акценти прибрані з усіх панелей.
		graphics.fill(x1, y1, x2, y1 + 1, PANEL_TOP_HIGHLIGHT);
	}

	/** Кути-дужки рамки (4 маленькі акцентні кути). */
	public static void corners(GuiGraphics graphics, int x1, int y1, int x2, int y2, int length, int color) {
		int len = Math.max(2, length);
		// верх-ліво
		graphics.fill(x1, y1, x1 + len, y1 + 1, color);
		graphics.fill(x1, y1, x1 + 1, y1 + len, color);
		// верх-право
		graphics.fill(x2 - len, y1, x2, y1 + 1, color);
		graphics.fill(x2 - 1, y1, x2, y1 + len, color);
		// низ-ліво
		graphics.fill(x1, y2 - 1, x1 + len, y2, color);
		graphics.fill(x1, y2 - len, x1 + 1, y2, color);
		// низ-право
		graphics.fill(x2 - len, y2 - 1, x2, y2, color);
		graphics.fill(x2 - 1, y2 - len, x2, y2, color);
	}

	/** Масштабує альфу ARGB-кольору, зберігаючи RGB. */
	public static int withAlpha(int argb, float alphaScale) {
		int a = (argb >>> 24) & 0xFF;
		// Кольори тексту в Minecraft традиційно задають без альфи (0xRRGGBB,
		// тобто старший байт = 0). Для них вважаємо колір повністю непрозорим.
		if (a == 0) a = 0xFF;
		int scaled = Math.round(a * Math.max(0f, Math.min(1f, alphaScale)));
		return (scaled << 24) | (argb & 0x00FFFFFF);
	}

	/**
	 * Один слот у стилі хотбара: темно-сіра «утоплена» комірка з чорною
	 * рамкою й світлою фаскою зверху-зліва. Розмір задається викликом
	 * (зазвичай 20x20, як у ванільному хотбарі).
	 *
	 * <p>Це навмисно не текстура {@code container/slot}: вона світла й
	 * виглядає як інвентарна комірка, а не як квадратик хотбара.
	 */
	// Кольори слота в стилі хотбара: темна рамка, темно-сіра середина,
	// світла фаска зверху-зліва й темна знизу-справа. Ті самі кольори
	// використовує рамка, яку малюємо ПОВЕРХ 3D-моделі предмета.
	private static final int SLOT_BORDER = 0xFF4A4E55;
	private static final int SLOT_FILL = 0xFF1C1E22;
	private static final int SLOT_BEVEL_LIGHT = 0x35FFFFFF;
	private static final int SLOT_BEVEL_DARK = 0x70000000;

	/**
	 * Малює один слот у стилі хотбара: темно-сірий квадрат із рамкою та
	 * фаскою. Розмір передається викликом (у HUD — 20x20).
	 */
	public static void mcSlot(GuiGraphics graphics, int x, int y, int size) {
		if (size < 4) return;
		// Темно-сірий квадратик у стилі слота хотбара: чорна рамка,
		// сіра «утоплена» середина й світла фаска зверху-зліва.
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
		// Рамка слота поверх 3D-моделі предмета: темна зовні, світла
		// фаска зверху-зліва, темна знизу-справа — як у хотбарі.
		graphics.fill(0, 0, size, 1, SLOT_BORDER);
		graphics.fill(0, size - 1, size, size, SLOT_BORDER);
		graphics.fill(0, 0, 1, size, SLOT_BORDER);
		graphics.fill(size - 1, 0, size, size, SLOT_BORDER);
		graphics.fill(1, 1, size - 1, 2, SLOT_BEVEL_LIGHT);
		graphics.fill(1, 1, 2, size - 1, SLOT_BEVEL_LIGHT);
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

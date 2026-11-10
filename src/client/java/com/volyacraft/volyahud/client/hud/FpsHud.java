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

		// ОПТИМІЗАЦІЯ: список рядків панелі перевикористовується замість
		// нового ArrayList на кожен кадр.
		java.util.List<Line> lines = LINES;
		lines.clear();

		if (cfg.showFps) {
			int fps = mc.getFps();
			int color = fps >= 100 ? 0x55FF55 : fps >= 40 ? 0xFFFF55 : 0xFF5555;
			lines.add(new Line(fps + " FPS", color));
		}

		if (cfg.showPing && mc.player != null && mc.getConnection() != null) {
			net.minecraft.client.multiplayer.PlayerInfo info =
					mc.getConnection().getPlayerInfo(mc.player.getUUID());
			if (info != null) {
				int ping = info.getLatency();
				int color = ping < 80 ? 0x55FF55 : ping < 150 ? 0xFFFF55 : 0xFF5555;
				lines.add(new Line("Ping: " + ping + " мс", color));
			}
		}

		if (mc.player != null && (cfg.showCoordX || cfg.showCoordY || cfg.showCoordZ)) {
			// ОПТИМІЗАЦІЯ: String.format("%.1f") створює Formatter і парсить
			// формат щоразу — а це 3 рази на КОЖЕН кадр. Ручне форматування
			// без алокацій.
			StringBuilder sb = new StringBuilder();
			if (cfg.showCoordX) sb.append("X: ").append(oneDecimal(mc.player.getX())).append("  ");
			if (cfg.showCoordY) sb.append("Y: ").append(oneDecimal(mc.player.getY())).append("  ");
			if (cfg.showCoordZ) sb.append("Z: ").append(oneDecimal(mc.player.getZ()));
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

		// НОВА ФУНКЦІЯ: таймер "бойового тегу" — видно лише поки бій активний,
		// тому в спокійному стані рядок просто не додається (панель не займає
		// зайвого місця, якщо бою немає).
		if (mc.player != null && cfg.combatTimer && CombatTimer.isActive()) {
			lines.add(new Line("\u2694 Бій: " + CombatTimer.secondsLeft() + "с", 0xFF7777));
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
		// Фон можна вимкнути: тоді лишаються тільки цифри/літери, без
		// темної плашки й рамки. Відступи теж прибираємо, щоб текст не
		// висів у порожнечі.
		boolean background = cfg.infoPanelBackground;
		if (background) panelW += 13;

		int y = bottom ? (screenH - 6 - totalH) : 4;
		int x = right ? (screenW - panelW - 4) : 4;

		// Ручне зміщення, як і у всіх інших панелях (броня/мінімапа/бафи).
		x = HudStyle.clampX(x + cfg.infoPanelOffsetX, panelW, screenW);
		y = HudStyle.clampY(y + cfg.infoPanelOffsetY, totalH, screenH);

		if (background) {
			int panelX1 = x - 2;
			int panelY1 = y - 3;
			int panelX2 = x + panelW - 2;
			HudStyle.panelAccent(graphics, panelX1, panelY1, panelX2, y + totalH + 1, HudStyle.ACCENT_CYAN);
		}

		// Рядки навмисно не чергуються світлими смугами: на темній панелі
		// напівпрозора "зебра" виглядала як випадкова біла лінія поверх
		// координат. Панель має бути одним цільним блоком, а рядки — просто
		// йти один під одним.
		int rowY = y;
		for (Line line : lines) {
			rowY = drawLine(graphics, mc, line.text(), line.color(), x, rowY, right, panelW, background);
		}
	}

	/** Форматує число з одним знаком після коми без String.format. */
	private static String oneDecimal(double v) {
		long tenths = Math.round(v * 10.0);
		// Знак виносимо окремо: інакше для -1.2 (tenths = -12) ціла частина
		// дорівнювала -1, а "нормалізація" дробу давала 0.2 — координата
		// раптово ставала додатною. Окремий знак також зберігає мінус для
		// маленьких значень на кшталт -0.1.
		boolean negative = tenths < 0;
		long abs = Math.abs(tenths);
		return (negative ? "-" : "") + (abs / 10) + "." + (abs % 10);
	}

	private record Line(String text, int color) {
	}

	/** ОПТИМІЗАЦІЯ: перевикористовуваний список рядків інфо-панелі. */
	private static final java.util.List<Line> LINES = new java.util.ArrayList<>(8);

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

	private static int drawLine(GuiGraphics graphics, Minecraft mc, String text, int color, int panelX, int y,
			boolean right, int panelW, boolean background) {
		int textWidth = mc.font.width(text);
		int textX = right
				? (panelX + panelW - textWidth - (background ? 6 : 0))
				: (panelX + (background ? 5 : 0));
		graphics.drawString(mc.font, text, textX, y, color, true);
		return y + 11;
	}
}

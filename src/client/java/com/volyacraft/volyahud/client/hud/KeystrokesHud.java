package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/**
 * Компактна панель натиснутих клавіш (WASD + пробіл) і кліків миші.
 *
 * <p>Стан миші читається напряму з GLFW, а не з {@code KeyMapping}: у
 * ванільному {@code MouseHandler} кнопки атаки/використання не завжди
 * оновлюють {@code isDown()} достатньо передбачувано для HUD. Прямий запит
 * до вікна дає чесний стан ЛКМ/ПКМ у момент кадру.
 *
 * <p>CPS рахується за ковзним вікном в 1 секунду. Панель вимкнена за
 * замовчуванням, щоб не змінювати звичний вигляд HUD без згоди гравця.
 */
public final class KeystrokesHud {

	private static final int KEY = 18;
	private static final int GAP = 3;
	private static final int PAD = 4;

	private static boolean initialized = false;
	private static boolean leftDown = false;
	private static boolean rightDown = false;
	private static final ArrayDeque<Long> LEFT_CLICKS = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT_CLICKS = new ArrayDeque<>();

	private KeystrokesHud() {
	}

	/** Скидає стан між світами/сесіями, щоб старі кліки не «переїхали». */
	public static void reset() {
		initialized = false;
		leftDown = false;
		rightDown = false;
		LEFT_CLICKS.clear();
		RIGHT_CLICKS.clear();
	}

	public static void render(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.keystrokesHud || mc.player == null) return;

		pollMouse(mc);
		long now = System.nanoTime();
		int leftCps = cps(LEFT_CLICKS, now);
		int rightCps = cps(RIGHT_CLICKS, now);

		int gridW = KEY * 3 + GAP * 2;
		int panelW = gridW + PAD * 2;
		int panelH = PAD * 2 + KEY * 3 + GAP * 3 + 12;
		if (cfg.keystrokesShowCps) panelH += 11;

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		boolean right = cfg.keystrokesCorner == HudConfig.ScreenCorner.TOP_RIGHT
				|| cfg.keystrokesCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;
		boolean bottom = cfg.keystrokesCorner == HudConfig.ScreenCorner.BOTTOM_LEFT
				|| cfg.keystrokesCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;

		int x = right ? screenW - 8 - panelW : 8;
		int y = bottom ? screenH - 8 - panelH : 8;
		x = HudStyle.clampX(x + cfg.keystrokesOffsetX, panelW, screenW);
		y = HudStyle.clampY(y + cfg.keystrokesOffsetY, panelH, screenH);

		HudStyle.panel(graphics, x, y, x + panelW, y + panelH);

		int kx = x + PAD;
		int ky = y + PAD;
		key(graphics, mc, kx + KEY + GAP, ky, KEY, KEY, "W",
				mc.options.keyUp.isDown());
		ky += KEY + GAP;
		key(graphics, mc, kx, ky, KEY, KEY, "A", mc.options.keyLeft.isDown());
		key(graphics, mc, kx + KEY + GAP, ky, KEY, KEY, "S", mc.options.keyDown.isDown());
		key(graphics, mc, kx + (KEY + GAP) * 2, ky, KEY, KEY, "D", mc.options.keyRight.isDown());
		ky += KEY + GAP;
		key(graphics, mc, kx, ky, gridW, 12, "SPACE", mc.options.keyJump.isDown());
		ky += 12 + GAP;

		int mouseW = (gridW - GAP) / 2;
		key(graphics, mc, kx, ky, mouseW, KEY, "LMB", leftDown);
		key(graphics, mc, kx + mouseW + GAP, ky, gridW - mouseW - GAP, KEY, "RMB", rightDown);

		if (cfg.keystrokesShowCps) {
			String cpsText = "CPS " + leftCps + " / " + rightCps;
			graphics.drawCenteredString(mc.font, cpsText, x + panelW / 2,
					y + panelH - 11, 0xC8CDD4);
		}
	}

	private static void pollMouse(Minecraft mc) {
		long window = mc.getWindow() != null ? mc.getWindow().getWindow() : 0L;
		boolean left = window != 0L
				&& GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean right = window != 0L
				&& GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

		long now = System.nanoTime();
		if (!initialized) {
			// Якщо HUD увімкнули з уже затиснутою кнопкою — не зараховуємо
			// це як «клік», інакше CPS одразу стрибне на 1 без причини.
			leftDown = left;
			rightDown = right;
			initialized = true;
		} else {
			if (left && !leftDown) LEFT_CLICKS.addLast(now);
			if (right && !rightDown) RIGHT_CLICKS.addLast(now);
			leftDown = left;
			rightDown = right;
		}
		prune(LEFT_CLICKS, now);
		prune(RIGHT_CLICKS, now);
	}

	private static void prune(ArrayDeque<Long> clicks, long now) {
		while (!clicks.isEmpty() && now - clicks.peekFirst() > 1_000_000_000L) {
			clicks.removeFirst();
		}
	}

	private static int cps(ArrayDeque<Long> clicks, long now) {
		prune(clicks, now);
		return clicks.size();
	}

	private static void key(GuiGraphics graphics, Minecraft mc, int x, int y, int w, int h,
			String label, boolean down) {
		int border = down ? 0xFF9AA3AD : 0xFF4A4E55;
		int fill = down ? 0xFF4A4E55 : 0xFF1C1E22;
		graphics.fill(x, y, x + w, y + h, border);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		graphics.fill(x + 1, y + 1, x + w - 1, y + 2,
				down ? 0x60FFFFFF : 0x25FFFFFF);
		graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x50000000);
		graphics.drawCenteredString(mc.font, label, x + w / 2, y + (h - 8) / 2,
				down ? 0xFFFFFFFF : 0xFFD8DEE6);
	}
}

package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public class PvpRadar {

	/**
	 * Горизонтальна панель-індикатор гравців поблизу: крапка на ній показує,
	 * з якого боку відносно погляду знаходиться гравець (-90..90 градусів).
	 * Піднята вище за ХП/голод/досвід (з окремим фоном і рамкою), щоб не
	 * зливатися з ванільними барами й бути видимою завжди, коли хтось поруч.
	 */
	public static void render(GuiGraphics graphics, Minecraft mc) {
		Player self = mc.player;
		if (self == null || mc.level == null) return;

		HudConfig cfg = HudConfig.INSTANCE;
		// ОПТИМІЗАЦІЯ: раніше тут було ДВА окремих проходи по списку гравців
		// щокадру — спершу Stream.anyMatch() (з алокацією лямбд/ітератора
		// стріму лише щоб перевірити "чи взагалі є хтось поруч"), а потім ще
		// раз звичайний for-each нижче для самого малювання. Список гравців
		// на сервері вже й так невеликий, тому це не критично, але на
		// PvP-серверах із десятками гравців поруч це подвійна робота щокадру
		// без жодної користі. Тепер лише один прохід звичайним циклом:
		// одразу малюємо крапки й попутно рахуємо anyNearby.
		var players = mc.level.players();
		boolean anyNearby = false;
		for (int i = 0, n = players.size(); i < n; i++) {
			net.minecraft.client.player.AbstractClientPlayer other = players.get(i);
			if (!isRadarTarget(mc, self, other)) continue;
			if (other.distanceTo(self) <= cfg.pvpRadarActivationDistance) {
				anyNearby = true;
				break;
			}
		}
		if (!anyNearby) return;

		int barWidth = 200;
		int barHeight = 5;
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		// Достатньо високо над хотбаром/хп/голодом/досвідом (у ваніли це все
		// займає нижні ~54px), щоб панель ніколи не перекривалась з ними.
		// Ручне зміщення (як і в усіх інших панелях), щоб можна було
		// розминутись з іншими HUD-елементами.
		int left = HudStyle.clampX(screenW / 2 - barWidth / 2 + cfg.pvpRadarOffsetX, barWidth, screenW);
		int barY = HudStyle.clampY(screenH - 58 + cfg.pvpRadarOffsetY, barHeight, screenH);
		int centerX = left + barWidth / 2;

		// фон + рамка, щоб панель було чітко видно на будь-якому тлі
		HudStyle.panelAccent(graphics, left - 4, barY - 5, left + barWidth + 4, barY + barHeight + 4,
				HudStyle.ACCENT_CYAN);

		// Сам бар: темний "жолоб" із градієнтом, щоб крапки читалися навіть
		// на світлому небі/снігу.
		graphics.fillGradient(left, barY, left + barWidth, barY + barHeight,
				0x90262A31, 0x903A3F48);
		graphics.fill(left, barY, left + barWidth, barY + 1, 0x30FFFFFF);

		// Позначки -90 / -45 / 0 / +45 / +90 градусів. Центральна — яскравіша.
		for (int i = 0; i <= 4; i++) {
			int tickX = left + i * (barWidth / 4);
			boolean center = i == 2;
			int tickColor = center ? 0xB0FFFFFF : 0x55FFFFFF;
			int tickTop = center ? barY - 2 : barY - 1;
			int tickBottom = center ? barY + barHeight + 2 : barY + barHeight + 1;
			graphics.fill(tickX, tickTop, tickX + 1, tickBottom, tickColor);
		}
		// Маленький ромб у центрі — "прямо перед тобою".
		graphics.fill(centerX - 1, barY + 2, centerX + 2, barY + 3, 0xE0FFFFFF);

		// ПОКРАЩЕННЯ: раніше гравці позаду (|relYaw| > 90) просто ігнорувались —
		// у PvP це найнебезпечніша сліпа зона (хтось підкрадається ззаду, а
		// радар мовчить). Тепер вони показуються трикутним індикатором на тому
		// краю панелі, з якого боку перебувають, замість повного ігнору.
		boolean enemyBehindLeft = false;
		boolean enemyBehindRight = false;

		for (int i = 0, n = players.size(); i < n; i++) {
			net.minecraft.client.player.AbstractClientPlayer other = players.get(i);
			if (!isRadarTarget(mc, self, other)) continue;
			double dist = other.distanceTo(self);
			if (dist > cfg.pvpRadarActivationDistance) continue;

			double yawToOther = getYawTo(self, other);
			double relYaw = Mth.wrapDegrees(yawToOther - self.getYRot());

			float healthRatio = other.getMaxHealth() > 0 ? other.getHealth() / other.getMaxHealth() : 1f;
			int color = healthRatio < 0.3f ? HudStyle.ACCENT_RED
					: healthRatio < 0.6f ? 0xFFFFB454 : HudStyle.ACCENT_GREEN;

			if (Math.abs(relYaw) > 90) {
				if (relYaw > 0) enemyBehindRight = true; else enemyBehindLeft = true;
				continue;
			}

			int dotX = centerX + (int) (relYaw / 90.0 * (barWidth / 2));
			dotX = Mth.clamp(dotX, left + 2, left + barWidth - 2);
			// Темний контур + кольорова крапка: на градієнті й на яскравому
			// рельєфі така мітка завжди залишається помітною.
			graphics.fill(dotX - 3, barY - 4, dotX + 3, barY + barHeight + 4, 0xC01A1C20);
			graphics.fill(dotX - 2, barY - 3, dotX + 2, barY + barHeight + 3, color);
			graphics.fill(dotX - 1, barY - 2, dotX + 1, barY - 1, 0x80FFFFFF);
		}

		if (enemyBehindLeft) {
			drawBehindArrow(graphics, left - 9, barY + barHeight / 2, true);
		}
		if (enemyBehindRight) {
			drawBehindArrow(graphics, left + barWidth + 9, barY + barHeight / 2, false);
		}
	}

	/**
	 * Маленький трикутник-попередження "ворог ззаду з цього боку".
	 * Малюємо звичайними fill()-смужками: без шейдерів і без залежності від
	 * версії рендер-API. {@code tipX} — вістря, {@code pointsLeft} — напрямок.
	 */
	private static void drawBehindArrow(GuiGraphics graphics, int tipX, int centerY, boolean pointsLeft) {
		// Темніший контур на 1px більший за сам трикутник.
		for (int row = 0; row < 7; row++) {
			int half = row;
			int x = pointsLeft ? tipX + row : tipX - row;
			graphics.fill(x, centerY - half - 1, x + 1, centerY + half + 2, 0xB01A1C20);
		}
		for (int row = 0; row < 6; row++) {
			int half = row;
			int x = pointsLeft ? tipX + row : tipX - row;
			graphics.fill(x, centerY - half, x + 1, centerY + half + 1, HudStyle.ACCENT_RED);
		}
	}

	/** Гравці, яких справді варто показувати: не себе, не мертвих, не спектаторів, не невидимих. */
	private static boolean isRadarTarget(Minecraft mc, Player self,
			net.minecraft.client.player.AbstractClientPlayer other) {
		if (other == self || !other.isAlive() || other.isSpectator()) return false;
		if (other.isInvisibleTo(self)) return false;
		// Серверні НПС не є гравцями — не показуємо їх на радарі.
		return PlayerFilter.isRealPlayer(mc, other);
	}

	private static double getYawTo(Player from, Player to) {
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	}
}

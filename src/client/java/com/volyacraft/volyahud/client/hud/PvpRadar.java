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
			Player other = players.get(i);
			if (other == self) continue;
			if (other.distanceTo(self) <= cfg.pvpRadarActivationDistance) {
				anyNearby = true;
				break;
			}
		}
		if (!anyNearby) return;

		int barWidth = 182;
		int barHeight = 4;
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
		HudStyle.panel(graphics, left - 2, barY - 2, left + barWidth + 2, barY + barHeight + 2);
		graphics.fill(left, barY, left + barWidth, barY + barHeight, 0x40FFFFFF);
		graphics.fill(centerX - 1, barY - 2, centerX + 1, barY + barHeight + 2, 0x80FFFFFF); // мітка "прямо перед собою"

		// ПОКРАЩЕННЯ: раніше гравці позаду (|relYaw| > 90) просто ігнорувались —
		// у PvP це найнебезпечніша сліпа зона (хтось підкрадається ззаду, а
		// радар мовчить). Тепер вони показуються трикутним індикатором на тому
		// краю панелі, з якого боку перебувають, замість повного ігнору.
		boolean enemyBehindLeft = false;
		boolean enemyBehindRight = false;

		for (int i = 0, n = players.size(); i < n; i++) {
			Player other = players.get(i);
			if (other == self) continue;
			double dist = other.distanceTo(self);
			if (dist > cfg.pvpRadarActivationDistance) continue;

			double yawToOther = getYawTo(self, other);
			double relYaw = Mth.wrapDegrees(yawToOther - self.getYRot());
			int color = other.getHealth() < other.getMaxHealth() * 0.3f ? 0xFFFF5555 : 0xFF55FF55;

			if (Math.abs(relYaw) > 90) {
				if (relYaw > 0) enemyBehindRight = true; else enemyBehindLeft = true;
				continue;
			}

			int dotX = centerX + (int) (relYaw / 90.0 * (barWidth / 2));
			dotX = Mth.clamp(dotX, left, left + barWidth);
			graphics.fill(dotX - 2, barY - 3, dotX + 2, barY + barHeight + 3, color);
		}

		if (enemyBehindLeft) {
			drawBehindArrow(graphics, left - 6, barY + barHeight / 2, true);
		}
		if (enemyBehindRight) {
			drawBehindArrow(graphics, left + barWidth + 6, barY + barHeight / 2, false);
		}
	}

	/** Маленький трикутник-попередження "ворог ззаду з цього боку", намальований звичайними fill()-смужками (без зайвого шейдера). */
	private static void drawBehindArrow(GuiGraphics graphics, int tipX, int centerY, boolean pointsLeft) {
		int color = 0xFFFF5555;
		for (int row = 0; row < 5; row++) {
			int width = 5 - row;
			int y1 = centerY - width / 2;
			int y2 = centerY + width / 2 + 1;
			int x = pointsLeft ? tipX + row : tipX - row;
			int xw = pointsLeft ? x + 1 : x;
			graphics.fill(Math.min(x, xw), y1, Math.max(x, xw) + 1, y2, color);
		}
	}

	private static double getYawTo(Player from, Player to) {
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	}
}

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
		boolean anyNearby = mc.level.players().stream()
				.filter(p -> p != self)
				.anyMatch(p -> p.distanceTo(self) <= cfg.pvpRadarActivationDistance);
		if (!anyNearby) return;

		int barWidth = 182;
		int barHeight = 4;
		int centerX = graphics.guiWidth() / 2;
		// Достатньо високо над хотбаром/хп/голодом/досвідом (у ваніли це все
		// займає нижні ~54px), щоб панель ніколи не перекривалась з ними.
		int barY = graphics.guiHeight() - 58;
		int left = centerX - barWidth / 2;

		// фон + рамка, щоб панель було чітко видно на будь-якому тлі
		graphics.fill(left - 2, barY - 2, left + barWidth + 2, barY + barHeight + 2, 0x80000000);
		graphics.fill(left - 2, barY - 2, left + barWidth + 2, barY - 1, 0x60FFFFFF);
		graphics.fill(left, barY, left + barWidth, barY + barHeight, 0x40FFFFFF);
		graphics.fill(centerX - 1, barY - 2, centerX + 1, barY + barHeight + 2, 0x80FFFFFF); // мітка "прямо перед собою"

		for (Player other : mc.level.players()) {
			if (other == self) continue;
			double dist = other.distanceTo(self);
			if (dist > cfg.pvpRadarActivationDistance) continue;

			double yawToOther = getYawTo(self, other);
			double relYaw = Mth.wrapDegrees(yawToOther - self.getYRot());
			if (Math.abs(relYaw) > 90) continue; // позаду — не показуємо

			int dotX = centerX + (int) (relYaw / 90.0 * (barWidth / 2));
			dotX = Mth.clamp(dotX, left, left + barWidth);

			int color = other.getHealth() < other.getMaxHealth() * 0.3f ? 0xFFFF5555 : 0xFF55FF55;
			graphics.fill(dotX - 2, barY - 3, dotX + 2, barY + barHeight + 3, color);
		}
	}

	private static double getYawTo(Player from, Player to) {
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	}
}

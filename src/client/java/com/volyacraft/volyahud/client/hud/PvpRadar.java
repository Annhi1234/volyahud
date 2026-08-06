package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public class PvpRadar {

	/**
	 * Малює горизонтальну шкалу над хотбаром (там, де зазвичай досвід),
	 * з крапками для кожного гравця в радіусі — позиція крапки відповідає
	 * різниці між напрямком погляду і напрямком на гравця (-180..180 градусів).
	 * З'являється лише коли хтось справді поряд, щоб не заважати поза боєм.
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
		int centerX = graphics.guiWidth() / 2;
		int barY = graphics.guiHeight() - 32 - 4; // трохи вище хотбару, де зазвичай XP bar
		int left = centerX - barWidth / 2;

		graphics.fill(left, barY, left + barWidth, barY + 2, 0x66000000);

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
			graphics.fill(dotX - 1, barY - 2, dotX + 1, barY + 4, color);
		}
	}

	private static double getYawTo(Player from, Player to) {
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
	}
}

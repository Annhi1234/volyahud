package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class BuffHud {

	/**
	 * Ванільні власні ефекти малюються у правому верхньому куті.
	 * Тут малюємо ефекти цілі (на кого дивишся) у лівому верхньому куті —
	 * тобто з протилежного боку екрана.
	 *
	 * Малюємо кольоровим маркером + текстом (без іконок з текстур-атласу
	 * ефектів) — це найнадійніший варіант, бо API рендеру іконок ефектів
	 * (спрайти/текстур-менеджер) змінювався між збірками 1.21.x і легко
	 * "ламається" при найменшій розбіжності версії.
	 */
	public static void render(GuiGraphics graphics, Minecraft mc) {
		if (mc.player == null || !(mc.hitResult instanceof EntityHitResult entityHit)) return;
		if (mc.hitResult.getType() != HitResult.Type.ENTITY) return;
		if (!(entityHit.getEntity() instanceof LivingEntity target)) return;

		var effects = target.getActiveEffects();
		if (effects.isEmpty()) return;

		int x = 8;
		int y = 8;

		graphics.drawString(mc.font, target.getName(), x, y, 0xFFFFFF, true);
		y += 12;

		for (MobEffectInstance effect : effects) {
			int color = effect.getEffect().value().getColor();
			graphics.fill(x, y + 2, x + 8, y + 10, 0xFF000000 | color);

			String name = effect.getEffect().value().getDisplayName().getString();
			String amplifier = effect.getAmplifier() > 0 ? " " + toRoman(effect.getAmplifier() + 1) : "";
			String duration = formatDuration(effect.getDuration());

			graphics.drawString(mc.font, name + amplifier + " (" + duration + ")", x + 12, y, 0xFFFFFF, true);
			y += 12;
		}
	}

	private static String formatDuration(int ticks) {
		int seconds = ticks / 20;
		return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
	}

	private static String toRoman(int n) {
		String[] romans = {"I", "II", "III", "IV", "V"};
		return n >= 1 && n <= 5 ? romans[n - 1] : String.valueOf(n);
	}
}

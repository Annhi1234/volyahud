package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BuffHud {

	/** Ефекти з тривалістю понад це число тіків вважаємо "нескінченними" (∞), а не показуємо величезний час. */
	private static final int INFINITE_THRESHOLD_TICKS = 20 * 60 * 60; // 1 година
	private static final int MAX_LINES = 8;

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
		if (target == mc.player) return;

		List<MobEffectInstance> effects = new ArrayList<>(target.getActiveEffects());
		if (effects.isEmpty()) return;

		// Найкоротші за часом ефекти (ті, що от-от зникнуть) — зверху, це найважливіше в PvP.
		effects.sort(Comparator.comparingInt(MobEffectInstance::getDuration));

		int x = 8;
		int y = 8;
		String title = target.getName().getString();

		int lineCount = Math.min(effects.size(), MAX_LINES);
		int panelW = mc.font.width(title);
		for (int i = 0; i < lineCount; i++) {
			panelW = Math.max(panelW, mc.font.width(effectLine(effects.get(i))) + 12);
		}
		int panelH = 12 + lineCount * 12 + (effects.size() > MAX_LINES ? 12 : 0);

		graphics.fill(x - 4, y - 3, x + panelW + 4, y + panelH + 1, 0x80000000);

		graphics.drawString(mc.font, title, x, y, 0xFFFFFF, true);
		y += 12;

		for (int i = 0; i < lineCount; i++) {
			MobEffectInstance effect = effects.get(i);
			int color = effect.getEffect().value().getColor();
			graphics.fill(x, y + 2, x + 8, y + 10, 0xFF000000 | color);
			graphics.drawString(mc.font, effectLine(effect), x + 12, y, 0xFFFFFF, true);
			y += 12;
		}

		if (effects.size() > MAX_LINES) {
			graphics.drawString(mc.font, "+" + (effects.size() - MAX_LINES) + "...", x + 12, y, 0xAAAAAA, true);
		}
	}

	private static String effectLine(MobEffectInstance effect) {
		String name = effect.getEffect().value().getDisplayName().getString();
		String amplifier = effect.getAmplifier() > 0 ? " " + toRoman(effect.getAmplifier() + 1) : "";
		String duration = formatDuration(effect.getDuration());
		return name + amplifier + " (" + duration + ")";
	}

	private static String formatDuration(int ticks) {
		// Нескінченні/маячні ефекти (наприклад, з маяка чи /effect ... infinite)
		// раніше показувались як величезні чи від'ємні числа хвилин — тепер просто "∞".
		if (ticks < 0 || ticks >= INFINITE_THRESHOLD_TICKS) return "\u221E";
		int seconds = ticks / 20;
		return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
	}

	private static String toRoman(int n) {
		String[] romans = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 1 && n <= romans.length ? romans[n - 1] : String.valueOf(n);
	}
}

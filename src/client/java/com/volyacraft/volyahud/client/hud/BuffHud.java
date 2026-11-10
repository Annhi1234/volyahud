package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
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

	/** ОПТИМІЗАЦІЯ: перевикористовуваний список ефектів. */
	private static final List<MobEffectInstance> EFFECTS = new ArrayList<>(16);

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
		// Якщо ціль — "гравець", але серверний НПС (Citizens тощо), то
		// таб-листа для нього немає — тож це не справжній гравець.
		// Мобів (звичайний LivingEntity) фільтр не зачіпає: їхні бафи
		// показувати корисно.
		if (target instanceof net.minecraft.client.player.AbstractClientPlayer clientPlayer
				&& !PlayerFilter.isRealPlayer(mc, clientPlayer)) {
			return;
		}

		// ОПТИМІЗАЦІЯ: список ефектів перевикористовується замість нового
		// ArrayList на кожен кадр (буфайс працює щокадру, поки дивишся на
		// ціль).
		List<MobEffectInstance> effects = EFFECTS;
		effects.clear();
		effects.addAll(target.getActiveEffects());
		if (effects.isEmpty()) return;

		// Найкоротші за часом ефекти (ті, що от-от зникнуть) — зверху, це
		// найважливіше в PvP. Нескінченні (duration < 0 або дуже великий)
		// мають бути в КІНЦІ, інакше -1 сортувався першим і витісняв короткі
		// ефекти з видимих MAX_LINES рядків.
		effects.sort(Comparator.comparingInt(BuffHud::sortDuration));

		String title = trim(mc, target.getName().getString(), 180);
		String countText = String.valueOf(effects.size());

		int lineCount = Math.min(effects.size(), MAX_LINES);
		int panelW = Math.max(mc.font.width(title) + mc.font.width(countText) + 24, 96);
		for (int i = 0; i < lineCount; i++) {
			panelW = Math.max(panelW, effectLineWidth(mc, effects.get(i)) + 16);
		}
		int panelH = 18 + lineCount * 12 + (effects.size() > MAX_LINES ? 11 : 0) + 4;

		HudConfig cfg = HudConfig.INSTANCE;
		boolean right = cfg.targetBuffsCorner == HudConfig.ScreenCorner.TOP_RIGHT
				|| cfg.targetBuffsCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;
		boolean bottom = cfg.targetBuffsCorner == HudConfig.ScreenCorner.BOTTOM_LEFT
				|| cfg.targetBuffsCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		int x = right ? (screenW - panelW - 8) : 8;
		int y = bottom ? (screenH - panelH - 8) : 8;

		x = HudStyle.clampX(x + cfg.targetBuffsOffsetX, panelW, screenW);
		y = HudStyle.clampY(y + cfg.targetBuffsOffsetY, panelH, screenH);

		HudStyle.panelAccent(graphics, x - 4, y - 3, x + panelW - 4, y + panelH - 3,
				HudStyle.ACCENT_CYAN);

		int headerY = y;
		graphics.drawString(mc.font, title, x, headerY, 0xFFF4F6F8, true);
		int countW = mc.font.width(countText);
		int badgeX = x + panelW - countW - 12;
		HudStyle.badge(graphics, badgeX, headerY - 1, badgeX + countW + 6, headerY + 9,
				HudStyle.ACCENT_CYAN);
		graphics.drawString(mc.font, countText, badgeX + 3, headerY, 0xFFE8F8FF, true);
		HudStyle.divider(graphics, x, headerY + 11, x + panelW - 8);
		y = headerY + 15;

		for (int i = 0; i < lineCount; i++) {
			MobEffectInstance effect = effects.get(i);
			int color = effect.getEffect().value().getColor();
			// Окремий "чип" кольору ефекту з темним контуром — читається
			// набагато краще, ніж простий квадрат без рамки.
			graphics.fill(x, y + 2, x + 9, y + 10, 0xD01A1C20);
			graphics.fill(x + 1, y + 3, x + 8, y + 9, 0xFF000000 | color);
			graphics.fill(x + 1, y + 3, x + 8, y + 4, 0x50FFFFFF);

			String name = trim(mc, effectName(effect), 200);
			String duration = formatDuration(effect.getDuration());
			graphics.drawString(mc.font, name, x + 13, y, 0xFFF0F2F5, true);
			int durationColor = durationColor(effect.getDuration());
			graphics.drawString(mc.font, duration, x + 13 + mc.font.width(name) + 4, y,
					durationColor, true);
			y += 12;
		}

		if (effects.size() > MAX_LINES) {
			graphics.drawString(mc.font, "+" + (effects.size() - MAX_LINES) + " ще…", x + 13, y,
					HudStyle.TEXT_MUTED, true);
		}
	}

	private static int sortDuration(MobEffectInstance effect) {
		int ticks = effect.getDuration();
		return ticks < 0 || ticks >= INFINITE_THRESHOLD_TICKS ? Integer.MAX_VALUE : ticks;
	}

	private static int effectLineWidth(Minecraft mc, MobEffectInstance effect) {
		return 13 + mc.font.width(trim(mc, effectName(effect), 200)) + 4
				+ mc.font.width(formatDuration(effect.getDuration()));
	}

	private static String trim(Minecraft mc, String text, int maxWidth) {
		if (text == null || text.isEmpty() || mc.font.width(text) <= maxWidth) return text;
		return mc.font.plainSubstrByWidth(text, Math.max(0, maxWidth - 6)) + "\u2026";
	}

	private static int durationColor(int ticks) {
		if (ticks < 0) return HudStyle.ACCENT_CYAN;
		if (ticks <= 20 * 5) return HudStyle.ACCENT_RED;
		if (ticks <= 20 * 15) return HudStyle.ACCENT_GOLD;
		return 0xFFB8E0B8;
	}

	private static String effectName(MobEffectInstance effect) {
		String name = effect.getEffect().value().getDisplayName().getString();
		String amplifier = effect.getAmplifier() > 0 ? " " + toRoman(effect.getAmplifier() + 1) : "";
		return name + amplifier;
	}

	private static String effectLine(MobEffectInstance effect) {
		return effectName(effect) + " (" + formatDuration(effect.getDuration()) + ")";
	}

	private static String formatDuration(int ticks) {
		// Нескінченні/маячні ефекти (наприклад, з маяка чи /effect ... infinite)
		// раніше показувались як величезні чи від'ємні числа хвилин — тепер просто "∞".
		if (ticks < 0 || ticks >= INFINITE_THRESHOLD_TICKS) return "\u221E";
		int seconds = ticks / 20;
		int secs = seconds % 60;
		// БЕЗ String.format: він алокує масиви/форматери й викликається
		// щокадру для кожного ефекту цілі. Просте доповнення нулем дешевше.
		return (seconds / 60) + ":" + (secs < 10 ? "0" : "") + secs;
	}

	private static String toRoman(int n) {
		String[] romans = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 1 && n <= romans.length ? romans[n - 1] : String.valueOf(n);
	}
}

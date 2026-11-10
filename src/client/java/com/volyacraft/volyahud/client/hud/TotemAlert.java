package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Заповнюється з ClientPacketListenerMixin, коли приходить
 * ClientboundEntityEventPacket зі статусом 35 (спрацював тотем).
 * Ванільна гра НЕ приховує це від клієнта — партиклі/звук тотема все одно
 * летять усім навколо, це вже видима частина протоколу, тому це не read-хак,
 * просто зручне текстове сповіщення замість того, щоб вилавливати це очима.
 *
 * Малюємо власним текстом (замість ванільної системи toast'ів) — так надійніше,
 * бо API toast-менеджера змінюється між збірками і легше поламати компіляцію.
 */
public class TotemAlert {

	private record Alert(String text, long createdAtMs, long expiresAtMs) {
	}

	private static final int MAX_ALERTS = 5;
	private static final List<Alert> ACTIVE = new ArrayList<>();
	/** ОПТИМІЗАЦІЯ: кеш ширини тексту сповіщень (рахується один раз на кадр). */
	private static int[] widths = new int[8];
	/** Текст, обрізаний під ширину екрана (щоб банер не виліз за край). */
	private static String[] displayTexts = new String[8];

	public static void onTotemUsed(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || entity == mc.player) return;
		if (mc.player.distanceTo(entity) > 32) return;

		long now = System.currentTimeMillis();
		String text = entity.getName().getString() + " використав(ла) тотем!";
		// Сервер іноді дублює entity event у тому ж тіку — не плодимо однакові
		// банери one-to-one.
		for (int i = ACTIVE.size() - 1; i >= 0; i--) {
			Alert last = ACTIVE.get(i);
			if (last.text().equals(text) && now - last.createdAtMs() < 250) return;
		}
		while (ACTIVE.size() >= MAX_ALERTS) {
			ACTIVE.remove(0);
		}
		ACTIVE.add(new Alert(text, now, now + 3000));
	}

	public static void tick(Minecraft mc) {
		// ОПТИМІЗАЦІЯ: System.currentTimeMillis() викликався для КОЖНОГО
		// активного сповіщення — виносимо один знімок часу назовні.
		long now = System.currentTimeMillis();
		ACTIVE.removeIf(a -> now > a.expiresAtMs());
	}

	/** Скидає всі активні банери (наприклад, при зміні сервера/світу). */
	public static void clear() {
		ACTIVE.clear();
	}

	public static void render(GuiGraphics graphics, Minecraft mc) {
		if (ACTIVE.isEmpty()) return;
		HudConfig cfg = HudConfig.INSTANCE;

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		long now = System.currentTimeMillis();
		int rowHeight = 15;
		int totalH = ACTIVE.size() * rowHeight;

		// БАГ-ФІКС: раніше кламп по X рахувався так, ніби ширина банера — 0px
		// (не враховував реальний текст), тому найширші сповіщення все одно
		// могли вилізти за край екрана при зсуві ближче до краю. Тепер беремо
		// найширший з активних рядків, як для решти панелей.
		// ОПТИМІЗАЦІЯ: ширина тексту рахувалася двічі — один раз тут, і
		// ще раз у циклі малювання. Рахуємо один раз і зберігаємо в
		// паралельний масив.
		int maxWidth = 0;
		if (widths.length < ACTIVE.size()) {
			widths = new int[Math.max(ACTIVE.size(), widths.length * 2)];
			displayTexts = new String[widths.length];
		}
		// Довгий нік + текст можуть бути ширшими за екран. Обрізаємо сам
		// рядок (а не лише зсуваємо панель), інакше вона все одно не влазить.
		int maxTextWidth = Math.max(24, screenW - 24);
		for (int i = 0; i < ACTIVE.size(); i++) {
			Alert alert = ACTIVE.get(i);
			String text = alert.text();
			if (mc.font.width(text) > maxTextWidth) {
				text = mc.font.plainSubstrByWidth(text, Math.max(0, maxTextWidth - 6)) + "\u2026";
			}
			displayTexts[i] = text;
			int w = mc.font.width(text);
			widths[i] = w;
			maxWidth = Math.max(maxWidth, w);
		}

		// БАГ-ФІКС: HudStyle.clampX дозволяє панелі зсуватися майже повністю
		// за край (це нормально для HUD, який можна "витягнути" назад), але
		// для тотем-банера це давало вихід за правий край: клампився лівий
		// край тексту, а не вся панель разом із відступами й рамкою.
		// Рахуємо повну ширину панелі (текст + 4px з кожного боку + 1px
		// рамки HudStyle) і тримаємо її цілком на екрані.
		int panelPad = 6;
		int panelW = maxWidth + panelPad * 2 + 2;
		int panelX = Math.max(0, Math.min(screenW / 2 - maxWidth / 2 - panelPad + cfg.totemAlertOffsetX,
				screenW - panelW));
		int centerX = panelX + panelPad + maxWidth / 2;
		// Аналогічно по вертикалі: останній банер закінчується на
		// y + (size-1)*rowHeight + 11.
		int y = Math.max(0, Math.min(40 + cfg.totemAlertOffsetY, screenH - totalH - 3));

		for (int i = 0; i < ACTIVE.size(); i++) {
			Alert alert = ACTIVE.get(i);
			long age = now - alert.createdAtMs();
			long remaining = alert.expiresAtMs() - now;
			if (remaining <= 0) continue;

			float fadeIn = Math.min(1f, Math.max(0f, age / 160f));
			float fadeOut = Math.min(1f, Math.max(0f, remaining / 380f));
			float alpha = Math.min(fadeIn, fadeOut);
			if (alpha <= 0.02f) continue;

			int width = widths[i];
			int slide = (int) ((1f - fadeIn) * -6f);
			int rowY = y + slide;
			int x1 = centerX - width / 2 - panelPad;
			int x2 = centerX + width / 2 + panelPad;
			HudStyle.panelAccent(graphics, x1, rowY - 3, x2, rowY + 11,
					HudStyle.ACCENT_GOLD, alpha);
			graphics.drawCenteredString(mc.font, displayTexts[i], centerX, rowY,
					HudStyle.withAlpha(0xFFFFD700, alpha));
			y += rowHeight;
		}
	}
}

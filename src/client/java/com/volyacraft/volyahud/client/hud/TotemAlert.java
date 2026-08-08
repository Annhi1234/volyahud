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

	private record Alert(String text, long expiresAtMs) {
	}

	private static final List<Alert> ACTIVE = new ArrayList<>();

	public static void onTotemUsed(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || entity == mc.player) return;
		if (mc.player.distanceTo(entity) > 32) return;

		ACTIVE.add(new Alert(entity.getName().getString() + " використав(ла) тотем!", System.currentTimeMillis() + 3000));
	}

	public static void tick(Minecraft mc) {
		ACTIVE.removeIf(a -> System.currentTimeMillis() > a.expiresAtMs());
	}

	public static void render(GuiGraphics graphics, Minecraft mc) {
		if (ACTIVE.isEmpty()) return;
		HudConfig cfg = HudConfig.INSTANCE;

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		int totalH = ACTIVE.size() * 14;

		int centerX = HudStyle.clampX(screenW / 2 + cfg.totemAlertOffsetX, 0, screenW);
		int y = HudStyle.clampY(40 + cfg.totemAlertOffsetY, totalH, screenH);

		for (Alert alert : ACTIVE) {
			int width = mc.font.width(alert.text());
			HudStyle.panel(graphics, centerX - width / 2 - 4, y - 2, centerX + width / 2 + 4, y + 10);
			graphics.drawCenteredString(mc.font, alert.text(), centerX, y, 0xFFD700);
			y += 14;
		}
	}
}

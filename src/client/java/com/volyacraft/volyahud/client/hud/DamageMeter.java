package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Окрема панель "реальної" шкоди при критичних ударах: скільки HP цілі ми
 * ФАКТИЧНО зняли своїм критом, і скільки HP нам ФАКТИЧНО зняв критом
 * противник. Саме фактично втрачене здоров'я — а не "сирий" урон предмета
 * з таблиць (він не враховує броню, зачарування, абсорбцію, опір і т.д.).
 *
 * Звідки беруться дані:
 *  - "хто щойно завдав крит-удар" дізнаємось з ClientboundEntityEventPacket
 *    (id 4 = звичайний крит, id 5 = магічний крит) — сервер і так шле цей
 *    статус-івент ВСІМ навколо (щоб намалювати крит-партиклі), так само,
 *    як totem-алерт вже читає id 35. Це вже публічна частина протоколу,
 *    не read-хак. Ловиться в ClientPacketListenerMixin -> onCrit(...).
 *  - "по кому саме ми щойно вдарили" — з MultiPlayerGameModeMixin, який
 *    перехоплює клієнтський клік атаки по сутності -> onAttack(...).
 *
 * Порядок пакетів (важливо для логіки нижче): у Player.attack() метод
 * this.crit(target)/this.magicCrit(target) викликається ДО target.hurt(...),
 * тобто крит-івент клієнт отримує РАНІШЕ, ніж синхронізацію нового здоров'я
 * цілі. Тож у момент onCrit() здоров'я цілі в клієнта ще СТАРЕ — можна
 * зняти його як "до" і за кілька наступних тіків порівняти з "після".
 *
 * Це евристика (як і решта HUD-елементів мода, напр. PvpRadar/TotemAlert):
 * без офіційного пакета "скільки саме урону нанесено" ми зіставляємо крит-
 * івент із найближчою по часу ціллю атаки/власним здоров'ям гравця. У 99%
 * звичайних PvP-сутичок (один противник у ближньому бою) цього достатньо.
 */
public class DamageMeter {

	private record Pending(LivingEntity entity, float preHealth, long expiresAtMs) {
	}

	private record Event(String text, int color, long expiresAtMs) {
	}

	/** Скільки часу після нашого кліку атаки ще можна зв'язати ціль із крит-івентом, що прилетить пізніше (затримка мережі/пінг). */
	private static final long ATTACK_LINK_WINDOW_MS = 1500;
	/** Скільки часу після крит-івенту чекаємо на фактичне падіння HP, перш ніж відмовитись від запису. */
	private static final long WATCH_WINDOW_MS = 1000;
	/** Дистанція, у межах якої чужий крит вважаємо спрямованим по нас (ближній бій). */
	private static final double MELEE_RANGE = 6.0;
	/** Як довго готовий запис ще показується в HUD після того, як шкоду порахували. */
	private static final long EVENT_DISPLAY_MS = 3000;

	private static Entity lastAttackTarget;
	private static long lastAttackTargetAtMs;

	private static Pending pendingDealt;
	private static Pending pendingTaken;

	private static Event lastDealt;
	private static Event lastTaken;

	/** Викликається з MultiPlayerGameModeMixin у момент кліку атаки по сутності. */
	public static void onAttack(Entity target) {
		lastAttackTarget = target;
		lastAttackTargetAtMs = System.currentTimeMillis();
	}

	/** Викликається з ClientPacketListenerMixin для id 4 (крит) і id 5 (магічний крит). */
	public static void onCrit(Entity attacker) {
		Minecraft mc = Minecraft.getInstance();
		Player self = mc.player;
		if (self == null || attacker == null) return;
		long now = System.currentTimeMillis();

		if (attacker == self) {
			// Ми самі щойно завдали крит — зв'язуємо це з останньою ціллю,
			// по якій клікали (якщо клік був не надто давно).
			if (lastAttackTarget instanceof LivingEntity target
					&& !target.isRemoved()
					&& (now - lastAttackTargetAtMs) <= ATTACK_LINK_WINDOW_MS) {
				pendingDealt = new Pending(target, target.getHealth(), now + WATCH_WINDOW_MS);
			}
		} else if (attacker instanceof LivingEntity && self.distanceTo(attacker) <= MELEE_RANGE) {
			// Хтось поруч завдав крит, і це не ми — найімовірніше, по нас.
			pendingTaken = new Pending(self, self.getHealth(), now + WATCH_WINDOW_MS);
		}
	}

	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();

		if (pendingDealt != null) {
			if (now > pendingDealt.expiresAtMs() || pendingDealt.entity().isRemoved()) {
				pendingDealt = null;
			} else {
				float lost = pendingDealt.preHealth() - pendingDealt.entity().getHealth();
				if (lost > 0.01f) {
					String name = pendingDealt.entity().getName().getString();
					lastDealt = new Event("Завдано: " + fmt(lost) + " (" + name + ")", 0x55FF55, now + EVENT_DISPLAY_MS);
					pendingDealt = null;
				}
			}
		}

		if (pendingTaken != null) {
			if (now > pendingTaken.expiresAtMs() || mc.player == null) {
				pendingTaken = null;
			} else {
				float lost = pendingTaken.preHealth() - mc.player.getHealth();
				if (lost > 0.01f) {
					lastTaken = new Event("Отримано: " + fmt(lost), 0xFF5555, now + EVENT_DISPLAY_MS);
					pendingTaken = null;
				}
			}
		}

		if (lastDealt != null && now > lastDealt.expiresAtMs()) lastDealt = null;
		if (lastTaken != null && now > lastTaken.expiresAtMs()) lastTaken = null;
	}

	private static String fmt(float hp) {
		return Math.abs(hp - Math.round(hp)) < 0.05f ? String.valueOf(Math.round(hp)) : String.format("%.1f", hp);
	}

	public static void render(GuiGraphics graphics, Minecraft mc) {
		if (lastDealt == null && lastTaken == null) return;
		HudConfig cfg = HudConfig.INSTANCE;

		List<Event> lines = new ArrayList<>();
		if (lastDealt != null) lines.add(lastDealt);
		if (lastTaken != null) lines.add(lastTaken);

		float scale = cfg.damageMeterScale;
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		int lineHeight = Math.round(11 * scale);
		int totalH = lines.size() * lineHeight;
		int panelW = 0;
		for (Event e : lines) {
			panelW = Math.max(panelW, Math.round(mc.font.width(e.text()) * scale));
		}
		panelW += 8;

		int x, y;
		if (cfg.damageMeterAboveCrosshair) {
			// По центру екрана, трохи вище прицілу (сам приціл стоїть точно
			// в центрі екрана, тому беремо запас, щоб не малювати поверх нього).
			x = HudStyle.clampX(screenW / 2 - panelW / 2 + cfg.damageMeterOffsetX, panelW, screenW);
			y = HudStyle.clampY(screenH / 2 - 24 - totalH + cfg.damageMeterOffsetY, totalH, screenH);
		} else {
			boolean right = cfg.damageMeterCorner == HudConfig.ScreenCorner.TOP_RIGHT
					|| cfg.damageMeterCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;
			boolean bottom = cfg.damageMeterCorner == HudConfig.ScreenCorner.BOTTOM_LEFT
					|| cfg.damageMeterCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;
			int baseY = bottom ? (screenH - 6 - totalH) : 4;
			int baseX = right ? (screenW - panelW - 4) : 4;
			x = HudStyle.clampX(baseX + cfg.damageMeterOffsetX, panelW, screenW);
			y = HudStyle.clampY(baseY + cfg.damageMeterOffsetY, totalH, screenH);
		}

		HudStyle.panel(graphics, x - 2, y - 3, x + panelW - 2, y + totalH + 1);

		// Малюємо текст у власному масштабі (компактний дрібний шрифт),
		// той самий прийом, що й у ArmorHud для підпису % міцності предмета.
		graphics.pose().pushPose();
		graphics.pose().translate(x, y, 0);
		graphics.pose().scale(scale, scale, 1f);
		int ly = 0;
		for (Event e : lines) {
			graphics.drawString(mc.font, e.text(), 2, ly, e.color(), true);
			ly += 11;
		}
		graphics.pose().popPose();
	}
}

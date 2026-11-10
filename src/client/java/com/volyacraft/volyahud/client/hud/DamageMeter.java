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
	private static final long ATTACK_LINK_WINDOW_MS = 2000;
	/** Скільки часу після крит-івенту чекаємо на фактичне падіння HP, перш ніж відмовитись від запису (враховує пінг). */
	private static final long WATCH_WINDOW_MS = 2500;
	/** Як довго готовий запис ще показується в HUD після того, як шкоду порахували. */
	private static final long EVENT_DISPLAY_MS = 3000;

	private static Entity lastAttackTarget;
	private static long lastAttackTargetAtMs;

	private static Pending pendingDealt;
	private static Pending pendingTaken;

	private static Event lastDealt;
	private static Event lastTaken;

	/** ОПТИМІЗАЦІЯ: перевикористовуваний список рядків панелі (макс. 2). */
	private static final List<Event> LINES = new ArrayList<>(2);

	/** Скидає всі незавершені/показані записи при зміні світу чи сервера. */
	public static void reset() {
		lastAttackTarget = null;
		lastAttackTargetAtMs = 0L;
		pendingDealt = null;
		pendingTaken = null;
		lastDealt = null;
		lastTaken = null;
		LINES.clear();
	}

	/** Викликається з MultiPlayerGameModeMixin у момент кліку атаки по сутності. */
	public static void onAttack(Entity target) {
		lastAttackTarget = target;
		lastAttackTargetAtMs = System.currentTimeMillis();
	}

	/**
	 * Викликається з ClientPacketListenerMixin для id 4 (крит) і id 5
	 * (магічний крит).
	 *
	 * <p><b>ВАЖЛИВО:</b> {@code ClientboundAnimatePacket} містить id
	 * СУТНОСТІ, ЯКУ АНІМУЮТЬ (тобто цілі удару), а не id атакуючого.
	 * Попередня версія вважала цю сутність атакуючим і через це плутала
	 * "завдано" з "отримано": наш крит по ворогу зараховувався як отримана
	 * шкода, а крит по нас — як завдана. Тепер:
	 * <ul>
	 *   <li>анімована сутність == ми → це крит ПО НАС;</li>
	 *   <li>анімована сутність == остання ціль нашої атаки → це НАШ крит;</li>
	 *   <li>будь-яка інша сутність → чужий крит по комусь іншому, ігноруємо.</li>
	 * </ul>
	 */
	public static void onCrit(Entity animatedEntity) {
		Minecraft mc = Minecraft.getInstance();
		Player self = mc.player;
		if (self == null || animatedEntity == null) return;
		long now = System.currentTimeMillis();

		if (animatedEntity == self) {
			// Крит по нас. Хто саме вдарив — з пакета не видно, але для
			// панелі достатньо зафіксувати наше HP "до" й дочекатись
			// синхронізації health.
			pendingTaken = new Pending(self, self.getHealth(), now + WATCH_WINDOW_MS);
			return;
		}

		// Наш крит: анімована сутність збігається з останньою ціллю кліку.
		if (animatedEntity instanceof LivingEntity target
				&& !target.isRemoved()
				&& target == lastAttackTarget
				&& (now - lastAttackTargetAtMs) <= ATTACK_LINK_WINDOW_MS) {
			pendingDealt = new Pending(target, target.getHealth(), now + WATCH_WINDOW_MS);
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

	/** ОПТИМІЗАЦІЯ: без String.format — він створює Formatter на кожен виклик. */
	private static String fmt(float hp) {
		long rounded = Math.round(hp);
		if (Math.abs(hp - rounded) < 0.05f) {
			return Long.toString(rounded);
		}
		long tenths = Math.round(hp * 10.0);
		// Знак виносимо окремо, як і в FpsHud.oneDecimal: старий код для
		// -1.2 давав "0.2" (ціла частина -1, а дробова "нормалізувалася"
		// до 2 із зайвим +1), тобто мінус у шкоді міг зникнути.
		boolean negative = tenths < 0;
		long abs = Math.abs(tenths);
		return (negative ? "-" : "") + (abs / 10) + "." + (abs % 10);
	}

	public static void render(GuiGraphics graphics, Minecraft mc) {
		if (lastDealt == null && lastTaken == null) return;
		HudConfig cfg = HudConfig.INSTANCE;

		// ОПТИМІЗАЦІЯ: список на 0-2 елементи виділявся щоКАДРУ. Тепер
		// перевикористовується статичний список (максимум 2 елементи).
		List<Event> lines = LINES;
		lines.clear();
		if (lastDealt != null) lines.add(lastDealt);
		if (lastTaken != null) lines.add(lastTaken);

		float scale = cfg.damageMeterScale;
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		long now = System.currentTimeMillis();
		int lineHeight = Math.max(7, Math.round(11 * scale));
		int totalH = lines.size() * lineHeight;
		int panelW = 0;
		for (Event e : lines) {
			panelW = Math.max(panelW, Math.round(mc.font.width(e.text()) * scale));
		}
		// 8px базових відступів + 4px під кольорову смужку зліва.
		panelW += 12;

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

		// Плавне зникнення останні 600 мс: запис не "телепортується" з екрана,
		// а спокійно розчиняється. Панель тримає максимальну альфу рядків.
		float panelAlpha = 0f;
		for (Event e : lines) {
			panelAlpha = Math.max(panelAlpha, Math.min(1f, (e.expiresAtMs() - now) / 600f));
		}
		if (panelAlpha <= 0.02f) return;
		panelAlpha = Math.max(0f, Math.min(1f, panelAlpha));

		int x1 = x - 4;
		int y1 = y - 3;
		int x2 = x + panelW - 4;
		int y2 = y + totalH + 2;
		HudStyle.panelAccent(graphics, x1, y1, x2, y2, HudStyle.ACCENT_CYAN, panelAlpha);

		// Малюємо текст у власному масштабі (компактний дрібний шрифт),
		// той самий прийом, що й у ArmorHud для підпису % міцності предмета.
		graphics.pose().pushPose();
		graphics.pose().translate(x, y, 0);
		graphics.pose().scale(scale, scale, 1f);
		int ly = 0;
		for (Event e : lines) {
			float lineAlpha = Math.max(0f, Math.min(1f, (e.expiresAtMs() - now) / 600f));
			graphics.drawString(mc.font, e.text(), 3, ly, HudStyle.withAlpha(e.color(), lineAlpha), true);
			ly += 11;
		}
		graphics.pose().popPose();

		// Кольорова смужка на початку кожного рядка: зелена = ми завдали,
		// червона = отримали. Додатково до кольору тексту — швидко читається
		// навіть боковим зором.
		for (int i = 0; i < lines.size(); i++) {
			Event e = lines.get(i);
			float lineAlpha = Math.max(0f, Math.min(1f, (e.expiresAtMs() - now) / 600f));
			int rowY = y + i * lineHeight;
			// Смужка йде ОДРАЗУ ЗА акцентною смугою панелі, а не поверх неї —
			// інакше зелена/червона заливка повністю з'їдала бірзову рамку.
			graphics.fill(x1 + 2, rowY, x1 + 4, Math.min(y2 - 1, rowY + lineHeight - 2),
					HudStyle.withAlpha(e.color(), lineAlpha * 0.9f));
		}
	}
}

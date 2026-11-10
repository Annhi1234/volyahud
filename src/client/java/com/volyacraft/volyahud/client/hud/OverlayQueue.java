package com.volyacraft.volyahud.client.hud;

import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * WorldRenderEvents (3D) виконується раніше за HudRenderCallback (2D) у тому ж
 * кадрі, тому тут просто зберігаємо вже спроєктовані на екран координати
 * гравців, а малюємо текст/іконки вже у 2D-проході (там простіше й дешевше
 * малювати текст/предмети, ніж напряму у world render context).
 */
public final class OverlayQueue {

	public record Entry(int x, int y, Player player, double distance) {
	}

	/**
	 * ОПТИМІЗАЦІЯ: раніше drain() щоКАДРУ створював копію списку (new
	 * ArrayList<>(QUEUE)) — тобто на кожен кадр ми алокували новий масив і
	 * переносили в нього всі записи. Тепер працюємо з ДВОМУ списками, які
	 * міняються місцями: наповнюваний та той, що читається. Копіювання
	 * зникає повністю, а список, який віддаємо, просто очищаємо після
	 * обходу (він наступного кадру знову стане наповнюваним).
	 */
	private static final List<Entry> QUEUE = new ArrayList<>();
	private static final List<Entry> DRAINED = new ArrayList<>();

	private OverlayQueue() {
	}

	public static void queue(int x, int y, Player player, double distance) {
		// Захист від необмеженого росту, якщо 2D-прохід із якоїсь причини
		// не викликається (наприклад, інший мод скасував HUD-рендер).
		if (QUEUE.size() >= 512) return;
		QUEUE.add(new Entry(x, y, player, distance));
	}

	/**
	 * Повертає накопичені записи й очищає чергу. Список, який повертається,
	 * НЕ можна утримувати довше наступного виклику drain() — наступного разу
	 * він буде очищений (щоб не алокувати копію щокадру).
	 */
	public static List<Entry> drain() {
		List<Entry> result = DRAINED;
		result.clear();
		result.addAll(QUEUE);
		QUEUE.clear();
		return result;
	}

	/** Повністю очищає чергу (вихід зі світу / вимкнення HUD). */
	public static void clear() {
		QUEUE.clear();
		DRAINED.clear();
	}
}

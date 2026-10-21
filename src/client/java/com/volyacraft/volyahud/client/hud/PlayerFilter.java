package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Відрізняє <b>справжніх гравців</b> від серверних НПС.
 *
 * <p>На серверах з NPC (наприклад, плагіни на кшталт Citizens) броня, HP та
 * предмет у руках показувалися б і над мобами-«гравцями», що:
 * <ul>
 *   <li>заважало в PvP — купа броні над мобами плуталася з реальними
 *       противниками;</li>
 *   <li>ще й виглядало дивно: серверний NPC не має справжнього інвентаря,
 *       тож показувати йому броню — це показувати порожні слоти.</li>
 * </ul>
 *
 * <p>Критерій: сутність вважається гравцем, тільки якщо її UUID є в
 * <b>таб-листі</b> ({@code getListedOnlinePlayers()}) — тобто сервер
 * повідомив клієнта про реального гравця. Моб/NPC такого UUID не має
 * (або він не в таб-листі), тож відсіюється.
 *
 * <p>Якщо таб-лист недоступний (наприклад, гра в одиночному режимі — там
 * гравців немає взагалі), перевірка м'яко повертає {@code true}: щоб не
 * зламати відображення броні в одиночному режимі без таб-листа.
 */
public final class PlayerFilter {

	private PlayerFilter() {
	}

	/**
	 * Кеш UUID справжніх гравців із таб-листа. Оновлюється раз на тік —
	 * таб-лист змінюється рідко, а `getListedOnlinePlayers()` щоразу
	 * збирає нову колекцію, тож кеш економить алокації в гарячому 3D-проході.
	 */
	private static Set<UUID> realPlayers = new HashSet<>();
	/** Час останнього оновлення кешу (мс). */
	private static long cacheAtMs = 0L;
	/** Як часто оновлювати кеш (мс). */
	private static final long CACHE_INTERVAL_MS = 1000L;

	/**
	 * Чи ця сутність — справжній гравець (а не НПС/моб)?
	 */
	public static boolean isRealPlayer(Minecraft mc, AbstractClientPlayer player) {
		if (player == null) return false;
		if (player == mc.player) return true; // це ти сам — гравцем звісно

		refreshCache(mc);

		// Якщо таб-лист порожній (одиночна гра, або сервер не надсилав
		// жодного PlayerInfo) — не відсікаємо нікого, щоб не зламати HUD.
		if (realPlayers.isEmpty()) {
			return true;
		}

		return realPlayers.contains(player.getUUID());
	}

	/** Оновлює кеш UUID з таб-листа (раз на секунду). */
	private static void refreshCache(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now - cacheAtMs < CACHE_INTERVAL_MS) return;
		cacheAtMs = now;

		realPlayers.clear();

		if (mc.getConnection() == null) return;
		for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
			if (info != null && info.getProfile() != null) {
				realPlayers.add(info.getProfile().getId());
			}
		}
	}

	/** Скидає кеш (при переході в новий світ). */
	public static void reset() {
		realPlayers.clear();
		cacheAtMs = 0L;
	}
}

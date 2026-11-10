package com.volyacraft.volyahud.client.config;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Стан «своїх» чанків для overlay території.
 *
 * <p>Ключ тепер складається зі світу/сервера ТА виміру. Раніше він містив
 * лише вимір, тому позначки з одного сервера з'являлися на іншому сервері
 * з тим самим виміром (наприклад, на двох різних survival-серверах).
 *
 * <p>Старі конфіги з ключами лише за виміром мігруються у перший світ, у
 * який гравець заходить після оновлення ({@link #migrateLegacy(String)}).
 * Це єдиний безпечний варіант: старий формат не зберігав назву світу.
 */
public final class TerritoryManager {

	/** Розділювач, якого не буває в id виміру або в IP сервера. */
	private static final String KEY_SEPARATOR = "|";

	/**
	 * Лічильник змін. TerritoryBorder кешує побудовані лінії й перебудовує
	 * їх лише коли цей номер змінився (або змінився чанк/вимір/світ).
	 */
	private static int revision = 0;

	private TerritoryManager() {
	}

	public static int revision() {
		return revision;
	}

	public static void bumpRevision() {
		revision++;
	}

	private static String compositeKey(String worldKey, String dimensionKey) {
		String world = worldKey == null || worldKey.isBlank() ? "unknown_world" : worldKey;
		return world + KEY_SEPARATOR + dimensionKey;
	}

	/** Мітки території, які належать конкретному світу й виміру. */
	public static Set<Long> claimedIn(String worldKey, String dimensionKey) {
		Set<Long> set = HudConfig.INSTANCE.territoryChunks.get(compositeKey(worldKey, dimensionKey));
		return set == null ? Collections.emptySet() : set;
	}

	public static boolean isClaimed(String worldKey, String dimensionKey, int chunkX, int chunkZ) {
		return claimedIn(worldKey, dimensionKey).contains(ChunkPos.asLong(chunkX, chunkZ));
	}

	/** @return true, якщо чанк додано; false, якщо його прибрано. */
	public static boolean toggle(String worldKey, String dimensionKey, int chunkX, int chunkZ) {
		Set<Long> set = HudConfig.INSTANCE.territoryChunks
				.computeIfAbsent(compositeKey(worldKey, dimensionKey), k -> new HashSet<>());
		long key = ChunkPos.asLong(chunkX, chunkZ);
		boolean added;
		if (set.remove(key)) {
			added = false;
		} else {
			set.add(key);
			added = true;
		}
		bumpRevision();
		HudConfig.INSTANCE.save();
		return added;
	}

	/** Очищає позначки лише для поточного світу й виміру. */
	public static void clearWorldDimension(String worldKey, String dimensionKey) {
		HudConfig.INSTANCE.territoryChunks.remove(compositeKey(worldKey, dimensionKey));
		bumpRevision();
		HudConfig.INSTANCE.save();
	}

	public static int countInWorldDimension(String worldKey, String dimensionKey) {
		return claimedIn(worldKey, dimensionKey).size();
	}

	/**
	 * Переносить старі ключі (лише вимір) у новий формат «світ|вимір».
	 * Викликається після входу у світ, коли вже відомо, який це світ.
	 */
	public static void migrateLegacy(String worldKey) {
		Map<String, Set<Long>> all = HudConfig.INSTANCE.territoryChunks;
		boolean changed = false;
		for (String oldKey : new ArrayList<>(all.keySet())) {
			if (oldKey == null || oldKey.indexOf(KEY_SEPARATOR) >= 0) continue;
			Set<Long> old = all.remove(oldKey);
			if (old == null || old.isEmpty()) continue;
			all.computeIfAbsent(compositeKey(worldKey, oldKey), k -> new HashSet<>()).addAll(old);
			changed = true;
		}
		if (changed) {
			bumpRevision();
			HudConfig.INSTANCE.save();
		}
	}
}

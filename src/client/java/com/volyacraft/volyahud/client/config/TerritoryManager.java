package com.volyacraft.volyahud.client.config;

import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Зберігає, які чанки гравець позначив як "свою територію" (місто),
 * окремо для кожного виміру (щоб територія в Незері не плутались з тими
 * самими X/Z координатами в Оверворлді).
 *
 * ВАЖЛИВО: це НЕ читання даних плагіна Towny (чи будь-якого іншого) з
 * сервера — клієнтський мод фізично не бачить внутрішні дані серверного
 * плагіна, якщо сервер сам не надсилає їх окремим пакетом/API. Тому це
 * "ручна" система: граєш, стаєш у потрібному чанку, тиснеш J (або кнопку
 * в меню) — і саме цей чанк запам'ятовується як "своя територія" назавжди
 * (зберігається у volyahud.json, лишається між заходами у гру).
 */
public final class TerritoryManager {

	private TerritoryManager() {
	}

	public static boolean isClaimed(String dimensionKey, int chunkX, int chunkZ) {
		Set<Long> set = HudConfig.INSTANCE.territoryChunks.get(dimensionKey);
		return set != null && set.contains(ChunkPos.asLong(chunkX, chunkZ));
	}

	/** @return true, якщо чанк ЩОЙНО додано; false, якщо ЩОЙНО прибрано. */
	public static boolean toggle(String dimensionKey, int chunkX, int chunkZ) {
		Set<Long> set = HudConfig.INSTANCE.territoryChunks.computeIfAbsent(dimensionKey, k -> new HashSet<>());
		long key = ChunkPos.asLong(chunkX, chunkZ);
		boolean added;
		if (set.remove(key)) {
			added = false;
		} else {
			set.add(key);
			added = true;
		}
		HudConfig.INSTANCE.save();
		return added;
	}

	public static void clearDimension(String dimensionKey) {
		HudConfig.INSTANCE.territoryChunks.remove(dimensionKey);
		HudConfig.INSTANCE.save();
	}

	public static int countInDimension(String dimensionKey) {
		Set<Long> set = HudConfig.INSTANCE.territoryChunks.get(dimensionKey);
		return set == null ? 0 : set.size();
	}
}

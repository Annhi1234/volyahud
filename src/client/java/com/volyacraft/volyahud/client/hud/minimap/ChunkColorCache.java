package com.volyacraft.volyahud.client.hud.minimap;

import com.volyacraft.volyahud.client.VolyaHudClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Тримає для кожного виміру набір "уже досліджених" чанків: для кожного
 * стовпчика (x,z) чанка — колір найвищого непрозорого блока (той самий
 * підхід, яким ваніла малює кольори на предметі-мапі). НІЯКИХ сутностей
 * (мобів/гравців) тут не зберігається і не читається — лише блоки рельєфу.
 *
 * Дані тримаються в пам'яті під час сесії й додатково зберігаються на диск
 * (по одному файлу на "світ" — одиночну гру чи конкретний сервер), щоб
 * дослідженні чанки лишались на карті між заходами у гру.
 */
public final class ChunkColorCache {

	private static final Map<String, Map<Long, int[]>> CACHE = new ConcurrentHashMap<>();
	private static volatile String currentWorldKey = null;

	private ChunkColorCache() {
	}

	// ------------------------------------------------------------------
	// доступ до даних
	// ------------------------------------------------------------------

	public static int[] getOrNull(String dimensionKey, int chunkX, int chunkZ) {
		Map<Long, int[]> map = CACHE.get(dimensionKey);
		if (map == null) return null;
		return map.get(ChunkPos.asLong(chunkX, chunkZ));
	}

	public static Set<Long> chunksIn(String dimensionKey) {
		Map<Long, int[]> map = CACHE.get(dimensionKey);
		return map == null ? Collections.emptySet() : map.keySet();
	}

	// ------------------------------------------------------------------
	// захоплення кольорів рельєфу з клієнтського чанка
	// ------------------------------------------------------------------

	/** Знімає "фото" рельєфу одного чанка: 16x16 кольорів, по одному на стовпчик блоків. Мобів/гравців тут немає взагалі — лише BlockState. */
	public static void captureChunk(ClientLevel level, LevelChunk chunk) {
		String dim = level.dimension().location().toString();
		ChunkPos cp = chunk.getPos();
		int[] colors = new int[256];
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				colors[lz * 16 + lx] = columnColor(chunk, cp, lx, lz);
			}
		}
		CACHE.computeIfAbsent(dim, d -> new ConcurrentHashMap<>()).put(ChunkPos.asLong(cp.x, cp.z), colors);
	}

	private static int columnColor(LevelChunk chunk, ChunkPos cp, int localX, int localZ) {
		int worldX = cp.getMinBlockX() + localX;
		int worldZ = cp.getMinBlockZ() + localZ;
		int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
		if (topY < chunk.getMinY()) {
			return 0xFF141414; // немає рельєфу (наприклад, дно порожнього чанка) — темно-сірий
		}
		BlockPos pos = new BlockPos(worldX, topY, worldZ);
		BlockState state = chunk.getBlockState(pos);
		MapColor mapColor = state.getMapColor(chunk, pos);
		if (mapColor == MapColor.NONE) {
			return 0xFF1A1A1A;
		}
		int rgb = mapColor.calculateARGBColor(MapColor.Brightness.NORMAL);
		return 0xFF000000 | rgb;
	}

	// ------------------------------------------------------------------
	// збереження на диск (по одному файлу на світ/сервер)
	// ------------------------------------------------------------------

	public static void loadWorld(String worldKey) {
		currentWorldKey = worldKey;
		CACHE.clear();
		Path file = saveFile(worldKey);
		if (!Files.exists(file)) return;

		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
			int dimCount = in.readInt();
			for (int d = 0; d < dimCount; d++) {
				String dim = in.readUTF();
				int chunkCount = in.readInt();
				Map<Long, int[]> map = new ConcurrentHashMap<>();
				for (int i = 0; i < chunkCount; i++) {
					long key = in.readLong();
					int[] colors = new int[256];
					for (int p = 0; p < 256; p++) colors[p] = in.readInt();
					map.put(key, colors);
				}
				CACHE.put(dim, map);
			}
		} catch (IOException e) {
			VolyaHudClient.LOGGER.warn("VolyaHUD: не вдалося завантажити збережену карту ({})", e.toString());
		}
	}

	public static void saveWorld() {
		String worldKey = currentWorldKey;
		if (worldKey == null || CACHE.isEmpty()) return;
		Path file = saveFile(worldKey);

		try {
			Files.createDirectories(file.getParent());
			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
				out.writeInt(CACHE.size());
				for (Map.Entry<String, Map<Long, int[]>> dimEntry : CACHE.entrySet()) {
					out.writeUTF(dimEntry.getKey());
					out.writeInt(dimEntry.getValue().size());
					for (Map.Entry<Long, int[]> chunkEntry : dimEntry.getValue().entrySet()) {
						out.writeLong(chunkEntry.getKey());
						for (int c : chunkEntry.getValue()) out.writeInt(c);
					}
				}
			}
		} catch (IOException e) {
			VolyaHudClient.LOGGER.warn("VolyaHUD: не вдалося зберегти карту ({})", e.toString());
		}
	}

	public static void clearCurrentWorld() {
		CACHE.clear();
		if (currentWorldKey != null) {
			try {
				Files.deleteIfExists(saveFile(currentWorldKey));
			} catch (IOException ignored) {
				// не критично — файл просто перезапишеться при наступному saveWorld()
			}
		}
	}

	private static Path saveFile(String worldKey) {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("volyahud").resolve("maps").resolve(sanitize(worldKey) + ".dat");
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^a-zA-Z0-9_.-]+", "_");
	}
}
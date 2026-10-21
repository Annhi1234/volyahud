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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
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

		// ВАЖЛИВИЙ ФІКС: Heightmap у ванілі приймає ЛОКАЛЬНІ координати
		// всередині чанка (він сам маскує їх через "& 15"). Раніше ми передавали
		// СВІТОВІ координати (worldX/worldZ) — тобто для чанків, що не
		// починаються з 0, ми читали Heightmap ЗОВСІМ ІНШОГО стовпця
		// (через маскування), тобто рельєф на мінімапі був "змішаний" з
		// сусідніми чанками, а на виході за межі світу — вибивав у
		// "темний" колір. Тепер передаємо локальні координати, як ванільний
		// предмет-мапа.
		//
		// getHeight() повертає Y найвищого непрозорого блоку + 1, тобто
		// "повітря над поверхнею". Саме тому ми віднімаємо 1, щоб узяти
		// найвищий блок.
		int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
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

	/**
	 * ОПТИМІЗАЦІЯ (джерело періодичних "стутерів"/лагів): раніше збереження
	 * карти (GZIP-стиснення й запис на диск, викликається кожні ~30с з
	 * {@code VolyaHudClient} автозбереженням, а також при виході з гри)
	 * виконувалось СИНХРОННО прямо в ігровому клієнтському потоці. Для
	 * великої дослідженої карти (тисячі чанків, кожен по 256 int) це
	 * реальні мегабайти даних, і диск-I/O + GZIP-стиснення на головному
	 * потоці — це якраз та "коротка заморозка кожні 30 секунд", яку
	 * помічають як лаги. Дані для запису — це вже незалежний знімок
	 * (копія мапи), тому саме стиснення й запис можна спокійно виконати
	 * у фоновому потоці, не блокуючи рендер/тік.
	 * <p>
	 * Один виділений daemon-потік (не пул) — записи все одно послідовні
	 * за задумом (по одному файлу на світ), і daemon-статус гарантує, що
	 * потік не завадить JVM закритися, якщо гра вилітає саме в момент запису.
	 */
	private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(
			(ThreadFactory) r -> {
				Thread t = new Thread(r, "VolyaHUD-MapSave");
				t.setDaemon(true);
				return t;
			});

	/** Захист від накопичення застарілих задач у черзі, якщо збереження раптом не встигає за автозбереженнями (не мало б статись за звичайних умов, але про всяк випадок). */
	private static final AtomicBoolean SAVE_IN_PROGRESS = new AtomicBoolean(false);

	public static void saveWorld() {
		String worldKey = currentWorldKey;
		if (worldKey == null || CACHE.isEmpty()) return;
		if (!SAVE_IN_PROGRESS.compareAndSet(false, true)) {
			// Попереднє збереження ще не завершилось — пропускаємо цей раз,
			// наступне автозбереження за ~30с однаково повторить спробу.
			return;
		}

		// Знімок посилань на мапи знімається одразу тут (у виклику з ігрового
		// потоку) — сам ConcurrentHashMap і масиви int[] всередині вже й так
		// потокобезпечні на читання, тому фоновому потоку достатньо цієї
		// неглибокої копії структури, без копіювання самих даних.
		Map<String, Map<Long, int[]>> snapshot = new HashMap<>(CACHE);

		SAVE_EXECUTOR.submit(() -> {
			try {
				writeToDisk(worldKey, snapshot);
			} catch (IOException e) {
				VolyaHudClient.LOGGER.warn("VolyaHUD: не вдалося зберегти карту ({})", e.toString());
			} finally {
				SAVE_IN_PROGRESS.set(false);
			}
		});
	}

	private static void writeToDisk(String worldKey, Map<String, Map<Long, int[]>> snapshot) throws IOException {
		Path file = saveFile(worldKey);
		Files.createDirectories(file.getParent());
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
			out.writeInt(snapshot.size());
			for (Map.Entry<String, Map<Long, int[]>> dimEntry : snapshot.entrySet()) {
				out.writeUTF(dimEntry.getKey());
				out.writeInt(dimEntry.getValue().size());
				for (Map.Entry<Long, int[]> chunkEntry : dimEntry.getValue().entrySet()) {
					out.writeLong(chunkEntry.getKey());
					for (int c : chunkEntry.getValue()) out.writeInt(c);
				}
			}
		}
	}

	/**
	 * Синхронна версія — використовується лише там, де потрібна ГАРАНТІЯ,
	 * що файл записаний до повернення з методу (наприклад, якщо гру
	 * закривають повністю і фоновий потік просто не встигне доробити).
	 * Викликається з {@code ClientPlayConnectionEvents.DISCONNECT}, де
	 * коротка затримка при виході з сервера прийнятна (це не ігровий кадр).
	 */
	public static void saveWorldBlocking() {
		String worldKey = currentWorldKey;
		if (worldKey == null || CACHE.isEmpty()) return;
		try {
			writeToDisk(worldKey, new HashMap<>(CACHE));
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
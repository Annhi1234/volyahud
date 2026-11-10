package com.volyacraft.volyahud.client.hud.minimap;

import com.volyacraft.volyahud.client.VolyaHudClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Holds, per dimension, the set of already-explored chunks: for every block
 * column (x,z) of a chunk — the map colour of the topmost coloured block,
 * the Y of that block and the water depth of the column. This is exactly the
 * data a vanilla map item keeps (see {@code MapItemSavedData#colors}), so the
 * map can be rendered with the authentic vanilla algorithm.
 *
 * <p>NOTHING about entities (mobs/players) is stored or read here — only
 * terrain blocks.
 *
 * <h2>Stored format</h2>
 * <ul>
 *   <li>{@code colorIds[lz*16+lx]} — {@code byte}, id in the {@link MapColor}
 *       palette (0 = {@code MapColor.NONE}, i.e. nothing coloured there).
 *       Stored as the id rather than a finished ARGB value, so the palette
 *       stays a single byte per column (a quarter of the old memory/disk
 *       cost) and the value never depends on the neighbouring columns.</li>
 *   <li>{@code colorY[lz*16+lx]} — {@code short}, Y of the block that
 *       supplied the colour. This is what the vanilla hillshading uses, so
 *       relief keeps its proper lighting.</li>
 *   <li>{@code fluidCount[lz*16+lx]} — {@code byte}, how many fluid blocks
 *       sit below the surface. Vanilla uses this to shade shallow water
 *       lighter than deep water.</li>
 * </ul>
 *
 * <p>The data lives in memory for the session and is additionally saved to
 * disk (one file per "world" — a singleplayer world or a specific server) so
 * explored chunks stay on the map between sessions.
 */
public final class ChunkColorCache {

	/**
	 * Data of one chunk: 256 colours (ids from the {@link MapColor} palette),
	 * 256 block heights and 256 water depths, in the same order as a vanilla
	 * map — index {@code lz*16 + lx}.
	 */
	public static final class ChunkData {
		/** id of the block colour in the {@link MapColor} palette (0 = {@code MapColor.NONE}). */
		public final byte[] colorIds;
		/** Y of the block that supplied the colour (used for terrain shading). */
		public final short[] colorY;
		/** Water depth of the column (0 when there is no water). */
		public final byte[] fluidCount;

		ChunkData(byte[] colorIds, short[] colorY, byte[] fluidCount) {
			this.colorIds = colorIds;
			this.colorY = colorY;
			this.fluidCount = fluidCount;
		}
	}

	private static final Map<String, Map<Long, ChunkData>> CACHE = new ConcurrentHashMap<>();
	private static volatile String currentWorldKey = null;

	/**
	 * Монотонна "версія" світу. Кожен {@link #loadWorld(String)} і
	 * {@link #clearCurrentWorld()} збільшують її; фонове збереження, яке
	 * почалося ДО зміни світу, побачить іншу версію й не буде писати старий
	 * знімок поверх нового (раніше саме це могло затерти свіжу мапу).
	 */
	private static final AtomicLong WORLD_GENERATION = new AtomicLong();

	/**
	 * Чанки, у яких дані змінилися, разом із номером останньої зміни.
	 *
	 * <p>ОПТИМІЗАЦІЯ (важливо для слабких машин): раніше поява кожного
	 * нового чанка запускала ПОВНУ перебудову картинки — тобто перечитування
	 * 512x512 = 262144 блоків. Але новий чанк містить лише 256 блоків, тож
	 * ми робили в 1000 разів більше роботи, ніж потрібно. До того ж
	 * перечитування відбувалося кожні 2 секунди під час ходьби — тобто
	 * регулярні мікрофризи на слабкому ПК.
	 *
	 * <p>БАГ-ФІКС: раніше черга була «одноразовою» ({@code takeDirtyChunks}
	 * очищав її). Через це мінімапа й повноекранна карта, які малюються в
	 * одному кадрі, «з'їдали» зміни одна в одної — карта, відкрита після
	 * мінімапи, могла не бачити нових чанків, доки її не перетягнути. Тепер
	 * кожен споживач запам'ятовує свій номер версії й бачить усі зміни, які
	 * сталися після нього (черга ніколи не очищається «для всіх»).
	 */
	private static final Map<String, Map<Long, Long>> DIRTY_CHUNKS = new ConcurrentHashMap<>();

	/** Монотонний номер зміни — ним позначається кожен оновлений чанк. */
	private static final java.util.concurrent.atomic.AtomicLong DIRTY_SEQUENCE =
			new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Замок, що робить «позначити зміну» й «зняти знімок змін» атомарними
	 * одне відносно одного. Без нього можна було б узяти версію N, не
	 * побачити чанк, який ще не встиг записатися з версією N, і назавжди
	 * пропустити цю зміну (курсор уже N).
	 */
	private static final Object DIRTY_LOCK = new Object();

	/** Позначає чанк як «треба перемалювати». */
	public static void markDirty(String dimensionKey, java.util.Collection<Long> chunkKeys) {
		if (chunkKeys == null || chunkKeys.isEmpty()) return;
		synchronized (DIRTY_LOCK) {
			long sequence = DIRTY_SEQUENCE.incrementAndGet();
			Map<Long, Long> dirty = DIRTY_CHUNKS.computeIfAbsent(dimensionKey, d -> new ConcurrentHashMap<>());
			for (long key : chunkKeys) {
				dirty.put(key, sequence);
			}
		}
	}

	/** Позначає чанк як «треба перемалювати». */
	public static void markDirty(String dimensionKey, long chunkKey) {
		synchronized (DIRTY_LOCK) {
			long sequence = DIRTY_SEQUENCE.incrementAndGet();
			DIRTY_CHUNKS.computeIfAbsent(dimensionKey, d -> new ConcurrentHashMap<>())
					.put(chunkKey, sequence);
		}
	}

	/**
	 * Атомарний знімок: і набір чанків, змінених після {@code since}, і
	 * версія, яку можна безпечно запам'ятати як «оброблено».
	 *
	 * @param since версія, яку споживач уже обробив (0 — усі відомі зміни)
	 */
	public record DirtySnapshot(Set<Long> chunks, long sequence) {
	}

	public static DirtySnapshot dirtySnapshot(String dimensionKey, long since) {
		synchronized (DIRTY_LOCK) {
			long sequence = DIRTY_SEQUENCE.get();
			Map<Long, Long> dirty = DIRTY_CHUNKS.get(dimensionKey);
			if (dirty == null || dirty.isEmpty()) {
				return new DirtySnapshot(Collections.emptySet(), sequence);
			}
			Set<Long> result = null;
			for (Map.Entry<Long, Long> entry : dirty.entrySet()) {
				if (entry.getValue() <= since) continue;
				if (result == null) result = new java.util.HashSet<>();
				result.add(entry.getKey());
			}
			return new DirtySnapshot(result == null ? Collections.emptySet() : result, sequence);
		}
	}

	/** File magic "VHM2" — the current format (colour ids + heights + fluid depth). */
	private static final int MAGIC_V2 = 0x56484D32;
	/** File magic "VHM1" — the previous format (colour ids + heights). */
	private static final int MAGIC_V1 = 0x56484D31;
	/** File magic "VHM0" — the oldest format (ready-made ARGB colours). */
	private static final int MAGIC_V0 = 0x56484D30;

	private ChunkColorCache() {
	}

	// ------------------------------------------------------------------
	// data access
	// ------------------------------------------------------------------

	/** Chunk data, or null when the chunk has not been explored yet. */
	public static ChunkData get(String dimensionKey, int chunkX, int chunkZ) {
		Map<Long, ChunkData> map = CACHE.get(dimensionKey);
		if (map == null) return null;
		return map.get(ChunkPos.asLong(chunkX, chunkZ));
	}

	/** Keys of every explored chunk in the dimension. */
	public static Set<Long> chunksIn(String dimensionKey) {
		Map<Long, ChunkData> map = CACHE.get(dimensionKey);
		return map == null ? Collections.emptySet() : map.keySet();
	}

	// ------------------------------------------------------------------
	// capturing terrain colours from a client chunk
	// ------------------------------------------------------------------

	/**
	 * Takes a "photo" of one chunk's terrain: 16x16 colours (one per block
	 * column), 16x16 heights and 16x16 water depths. No mobs/players are
	 * involved at all — only {@link BlockState}s.
	 *
	 * <p>OPTIMISATION: previously every column ended up as a finished ARGB
	 * int (4 bytes each) and the height was thrown away, so the map cache
	 * used four times more memory than needed and the disk file was four
	 * times bigger than it had to be — a map can easily hold thousands of
	 * chunks. Now we store the palette id (1 byte) plus the two extra values
	 * the renderer actually needs, which also lets the terrain shading be
	 * computed correctly when the map is drawn.
	 */
	public static void captureChunk(ClientLevel level, ChunkAccess chunk) {
		String dim = level.dimension().location().toString();
		ChunkPos cp = chunk.getPos();

		byte[] colorIds = new byte[256];
		short[] colorYs = new short[256];
		byte[] fluidCounts = new byte[256];

		int minY = level.getMinY();
		// One mutable position reused for every column instead of allocating
		// 256 BlockPos objects per chunk.
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
		// One scratch object per chunk instead of 256 Column allocations.
		Column column = new Column();

		for (int lx = 0; lx < 16; lx++) {
			int worldX = cp.getMinBlockX() + lx;
			for (int lz = 0; lz < 16; lz++) {
				int worldZ = cp.getMinBlockZ() + lz;
				int idx = lz * 16 + lx;

				// IMPORTANT FIX: the vanilla Heightmap takes LOCAL coordinates
				// inside the chunk (it masks them with "& 15" itself). We used
				// to pass WORLD coordinates, so for any chunk not starting at
				// 0 we read the heightmap of a completely different column —
				// the relief on the map was a mix of neighbouring chunks and
				// turned dark outside the world origin. Now we pass local
				// coordinates, exactly like the vanilla map item does.
				//
				// chunk.getHeight(WORLD_SURFACE, x, z) returns the Y of the
				// topmost non-air block (the heightmap stores y+1 and the
				// accessor subtracts one).
				int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);

				captureColumn(chunk, worldX, worldZ, height, minY, pos, below, column);
				colorIds[idx] = (byte) column.colorId;
				colorYs[idx] = (short) column.colorY;
				fluidCounts[idx] = (byte) column.fluidCount;
			}
		}

		long key = ChunkPos.asLong(cp.x, cp.z);
		Map<Long, ChunkData> map = CACHE.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
		ChunkData data = new ChunkData(colorIds, colorYs, fluidCounts);
		ChunkData previous = map.put(key, data);
		// Позначаємо чанк «брудним» лише якщо дані СПРАВДІ змінилися.
		//
		// БАГ-ФІКС (мікрофризи що 2 секунди): опитувальний тік мінімапи
		// капчурить 7x7 чанків навколо гравця незалежно від того, чи щось
		// у них змінилося. Раніше КОЖЕН такий виклик ставив чанк у чергу
		// на перемальовування — тобто карта перебудовувала ~112 рядків
		// (57 000 пікселів) кожні 2 секунди навіть коли гравець просто
		// стоїть. Тепер незмінені чанки не турбують рендер узагалі.
		if (previous == null || !sameData(previous, data)) {
			markDirty(dim, key);
		}
	}

	/** Чи однакові два знімки чанка (щоб не перемальовувати карту даремно). */
	private static boolean sameData(ChunkData a, ChunkData b) {
		return java.util.Arrays.equals(a.colorIds, b.colorIds)
				&& java.util.Arrays.equals(a.colorY, b.colorY)
				&& java.util.Arrays.equals(a.fluidCount, b.fluidCount);
	}

	/** Result of analysing one block column. */
	private static final class Column {
		int colorId;
		int colorY;
		int fluidCount;
	}

	/**
	 * Works out the map colour of a single block column, the same way the
	 * vanilla map item does ({@code MapItem#update}):
	 *
	 * <ol>
	 *   <li>Start at the surface block and walk <b>down</b> while the block
	 *       has no map colour ({@code MapColor.NONE} — air, glass, glass
	 *       panes, ...). This is why the map shows the ground under glass
	 *       instead of a hole.</li>
	 *   <li>If the block we landed on contains a fluid, count the fluid blocks
	 *       below it. Vanilla's loop also counts the first non-fluid block
	 *       below the water (and the bedrock at minY), so the count is one
	 *       higher than the literal water depth; we keep that exactly.</li>
	 *   <li>If the block holds a fluid that is not "sturdy" on top, use the
	 *       fluid's legacy block instead — this is what the vanilla
	 *       {@code getCorrectStateForFluidBlock} does, so flowing water on
	 *       grass is blue on the map rather than green.</li>
	 * </ol>
	 */
	private static void captureColumn(ChunkAccess chunk,
			int worldX, int worldZ, int height, int minY,
			BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos below,
			Column result) {
		result.fluidCount = 0;

		// An empty column (nothing but air) has height == minY - 1. Vanilla
		// falls back to bedrock there, whose map colour is stone.
		if (height + 1 <= minY) {
			result.colorId = MapColor.STONE.id;
			result.colorY = minY;
			return;
		}

		int y = height;
		BlockState state = null;
		MapColor color = MapColor.NONE;
		while (true) {
			pos.set(worldX, y, worldZ);
			state = chunk.getBlockState(pos);
			color = state.getMapColor(chunk, pos);
			if (color != MapColor.NONE || y <= minY) break;
			y--;
		}

		if (color == MapColor.NONE) {
			// Nothing but colourless blocks down to the bottom of the world.
			result.colorId = MapColor.STONE.id;
			result.colorY = minY;
			return;
		}

		result.colorY = y;

		// Fluid handling — only for a column whose surface block holds a fluid.
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			int fluidCount = 0;
			int fy = y - 1;
			while (true) {
				below.set(worldX, fy, worldZ);
				BlockState belowState = chunk.getBlockState(below);
				fluidCount++;
				if (fy <= minY || belowState.getFluidState().isEmpty()) break;
				fy--;
			}
			result.fluidCount = fluidCount;

			// Use the fluid's legacy block when the block is not sturdy on top.
			if (!state.isFaceSturdy(chunk, pos, Direction.UP)) {
				state = fluid.createLegacyBlock();
				color = state.getMapColor(chunk, pos);
			}
		}

		result.colorId = color == null ? MapColor.NONE.id : color.id;
	}

	// ------------------------------------------------------------------
	// saving to disk (one file per world/server)
	// ------------------------------------------------------------------

	public static void loadWorld(String worldKey) {
		// Нова генерація робить усі ще не записані фонові знімки застарілими.
		WORLD_GENERATION.incrementAndGet();
		currentWorldKey = worldKey;
		CACHE.clear();
		DIRTY_CHUNKS.clear();
		Path file = saveFile(worldKey);
		if (!Files.exists(file)) return;

		// Читання під тим самим замком, що й запис: інакше можна було
		// прочитати файл рівно в момент, коли фоновий потік його переписує,
		// і отримати пошкоджену/обрізану мапу.
		synchronized (SAVE_LOCK) {
			try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
				int magic = in.readInt();
				switch (magic) {
					case MAGIC_V2 -> readV2(in);
					case MAGIC_V1 -> readV1(in);
					case MAGIC_V0 -> readV0(in);
					default -> VolyaHudClient.LOGGER.warn("VolyaHUD: unknown saved-map format, starting fresh");
				}
			} catch (IOException e) {
				VolyaHudClient.LOGGER.warn("VolyaHUD: could not load the saved map ({})", e.toString());
			}
		}
	}

	/** Current format: colour ids + heights + fluid depth. */
	private static void readV2(DataInputStream in) throws IOException {
		int dimCount = in.readInt();
		for (int d = 0; d < dimCount; d++) {
			String dim = in.readUTF();
			int chunkCount = in.readInt();
			Map<Long, ChunkData> map = new ConcurrentHashMap<>();
			for (int i = 0; i < chunkCount; i++) {
				long key = in.readLong();
				byte[] colorIds = new byte[256];
				short[] colorY = new short[256];
				byte[] fluidCount = new byte[256];
				in.readFully(colorIds);
				for (int p = 0; p < 256; p++) colorY[p] = in.readShort();
				in.readFully(fluidCount);
				map.put(key, new ChunkData(colorIds, colorY, fluidCount));
			}
			CACHE.put(dim, map);
		}
	}

	/**
	 * Previous format: colour ids + heights but no fluid depth. Read it so an
	 * already explored map is not lost; the missing water depth simply means
	 * old chunks get a flat water shade until the player walks over them
	 * again.
	 */
	private static void readV1(DataInputStream in) throws IOException {
		int dimCount = in.readInt();
		for (int d = 0; d < dimCount; d++) {
			String dim = in.readUTF();
			int chunkCount = in.readInt();
			Map<Long, ChunkData> map = new ConcurrentHashMap<>();
			for (int i = 0; i < chunkCount; i++) {
				long key = in.readLong();
				byte[] colorIds = new byte[256];
				short[] colorY = new short[256];
				in.readFully(colorIds);
				for (int p = 0; p < 256; p++) colorY[p] = in.readShort();
				map.put(key, new ChunkData(colorIds, colorY, new byte[256]));
			}
			CACHE.put(dim, map);
		}
	}

	/**
	 * Oldest format: ready-made ARGB colours without heights. We convert them
	 * back into palette ids so the rest of the code has a single format to
	 * deal with.
	 */
	private static void readV0(DataInputStream in) throws IOException {
		int dimCount = in.readInt();
		for (int d = 0; d < dimCount; d++) {
			String dim = in.readUTF();
			int chunkCount = in.readInt();
			Map<Long, ChunkData> map = new ConcurrentHashMap<>();
			for (int i = 0; i < chunkCount; i++) {
				long key = in.readLong();
				byte[] colorIds = new byte[256];
				for (int p = 0; p < 256; p++) {
					int argb = in.readInt();
					colorIds[p] = (byte) argbToColorId(argb);
				}
				map.put(key, new ChunkData(colorIds, new short[256], new byte[256]));
			}
			CACHE.put(dim, map);
		}
	}

	/** Reverse lookup from a colour to its id in the MapColor palette. */
	private static int argbToColorId(int argb) {
		Integer id = REVERSE_COLORS.get(argb & 0xFFFFFF);
		return id == null ? MapColor.NONE.id : id;
	}

	/** Cache of "colour without brightness" -> id in the MapColor palette. */
	private static final Map<Integer, Integer> REVERSE_COLORS = buildReverseColors();

	private static Map<Integer, Integer> buildReverseColors() {
		Map<Integer, Integer> map = new HashMap<>();
		for (int id = 0; id < 64; id++) {
			MapColor color;
			try {
				color = MapColor.byId(id);
			} catch (RuntimeException e) {
				continue; // no such id in the palette
			}
			if (color == MapColor.NONE) continue;
			int argb = color.calculateARGBColor(MapColor.Brightness.NORMAL);
			map.putIfAbsent(argb & 0xFFFFFF, id);
		}
		return map;
	}

	/**
	 * OPTIMISATION (source of the periodic "stutters"/lag): previously saving
	 * the map (GZIP compression and the disk write, called every ~30s from
	 * the autosave and also on leaving the game) ran SYNCHRONOUSLY on the
	 * client thread. For a large explored map (thousands of chunks) that is
	 * megabytes of data, and disk I/O plus GZIP compression on the main
	 * thread is exactly the "short freeze every 30 seconds" players notice as
	 * lag. The data being written is already an independent snapshot (a copy
	 * of the map), so the compression and the write can safely happen on a
	 * background thread without blocking rendering or ticking.
	 *
	 * <p>A single dedicated daemon thread (not a pool) — the writes are
	 * sequential by design (one file per world) anyway, and daemon status
	 * makes sure the thread never keeps the JVM alive if the game crashes
	 * mid-write.
	 */
	private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(
			(ThreadFactory) r -> {
				Thread t = new Thread(r, "VolyaHUD-MapSave");
				t.setDaemon(true);
				return t;
			});

	/** Guards against piling up stale tasks in the queue if a save ever falls behind. */
	private static final AtomicBoolean SAVE_IN_PROGRESS = new AtomicBoolean(false);

	/**
	 * Серіалізує записи у файл: фоновий автозейв і синхронний запис при
	 * виході зі світу можуть перетнутися. Без цього замка два GZIP-потоки
	 * писали б один і той самий .dat одночасно й файл міг залишитися
	 * пошкодженим (тобто вся збережена карта втрачалася).
	 */
	private static final Object SAVE_LOCK = new Object();

	public static void saveWorld() {
		String worldKey = currentWorldKey;
		if (worldKey == null || CACHE.isEmpty()) return;
		if (!SAVE_IN_PROGRESS.compareAndSet(false, true)) {
			// The previous save has not finished yet — skip this round, the
			// next autosave in ~30s will try again.
			return;
		}

		long generation = WORLD_GENERATION.get();
		// Snapshot must copy BOTH levels: copying only CACHE leaves the live
		// inner maps visible to the writer. A chunk captured between size()
		// and iteration then makes the file lie about its entry count and
		// corrupts the next dimension's header. ChunkData arrays are immutable
		// after capture, so sharing them is safe.
		Map<String, Map<Long, ChunkData>> snapshot = snapshotCache();

		SAVE_EXECUTOR.submit(() -> {
			try {
				writeToDisk(worldKey, snapshot, generation);
			} catch (IOException e) {
				VolyaHudClient.LOGGER.warn("VolyaHUD: could not save the map ({})", e.toString());
			} finally {
				SAVE_IN_PROGRESS.set(false);
			}
		});
	}

	/** Immutable-structure copy of the cache; safe to hand to the save thread. */
	private static Map<String, Map<Long, ChunkData>> snapshotCache() {
		Map<String, Map<Long, ChunkData>> snapshot = new HashMap<>();
		CACHE.forEach((dimension, chunks) -> snapshot.put(dimension, new HashMap<>(chunks)));
		return snapshot;
	}

	private static void writeToDisk(String worldKey, Map<String, Map<Long, ChunkData>> snapshot, long generation)
			throws IOException {
		synchronized (SAVE_LOCK) {
			// Поки знімок чекав у черзі, гравець міг вийти зі світу, зайти на
			// інший сервер або очистити мапу. Такий знімок уже нікому не
			// потрібен — інакше він затер би свіжіші дані.
			if (generation != WORLD_GENERATION.get() || !worldKey.equals(currentWorldKey)) return;

			Path file = saveFile(worldKey);
			Files.createDirectories(file.getParent());
			// Write beside the target and move atomically. A crash/power loss
			// during the old in-place write could truncate the only copy.
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try {
				try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
					out.writeInt(MAGIC_V2);
					out.writeInt(snapshot.size());
					for (Map.Entry<String, Map<Long, ChunkData>> dimEntry : snapshot.entrySet()) {
						out.writeUTF(dimEntry.getKey());
						out.writeInt(dimEntry.getValue().size());
						for (Map.Entry<Long, ChunkData> chunkEntry : dimEntry.getValue().entrySet()) {
							out.writeLong(chunkEntry.getKey());
							out.write(chunkEntry.getValue().colorIds);
							for (short h : chunkEntry.getValue().colorY) out.writeShort(h);
							out.write(chunkEntry.getValue().fluidCount);
						}
					}
				}
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
							StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
	}

	/**
	 * Synchronous version — used only where the file MUST be written before
	 * the method returns (for example when the game is closed completely and
	 * the background thread might not get around to it). Called from
	 * {@code ClientPlayConnectionEvents.DISCONNECT}, where a short delay on
	 * leaving a server is acceptable (this is not a game frame).
	 *
	 * <p>Важливо: запис іде через ТОЙ САМИЙ однопотоковий executor, що й
	 * фоновий автозейв. Так синхронний знімок гарантовано виконується після
	 * вже поставленого в чергу старішого, а не навпаки (раніше старий
	 * фоновий знімок міг записатися ПІСЛЯ нового й затерти його).
	 */
	public static void saveWorldBlocking() {
		String worldKey = currentWorldKey;
		if (worldKey == null || CACHE.isEmpty()) return;

		Map<String, Map<Long, ChunkData>> snapshot = snapshotCache();
		long generation = WORLD_GENERATION.get();

		// saveWorldBlocking() викликається з ігрового потоку, але захист від
		// самоблокування лишаємо — раптом колись його покличуть із потоку
		// збереження.
		if ("VolyaHUD-MapSave".equals(Thread.currentThread().getName())) {
			try {
				writeToDisk(worldKey, snapshot, generation);
			} catch (IOException e) {
				VolyaHudClient.LOGGER.warn("VolyaHUD: could not save the map ({})", e.toString());
			}
			return;
		}

		Future<?> future = SAVE_EXECUTOR.submit(() -> {
			try {
				writeToDisk(worldKey, snapshot, generation);
			} catch (IOException e) {
				VolyaHudClient.LOGGER.warn("VolyaHUD: could not save the map ({})", e.toString());
			}
		});
		try {
			future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (java.util.concurrent.ExecutionException e) {
			VolyaHudClient.LOGGER.warn("VolyaHUD: could not save the map ({})", e.getCause());
		}
	}

	public static void clearCurrentWorld() {
		// Спершу "інвалідуємо" всі знімки, які ще стоять у черзі, і лише
		// потім чистимо пам'ять/файл. Інакше фоновий запис міг закінчитися
		// після очищення й знову створити старий .dat.
		WORLD_GENERATION.incrementAndGet();
		CACHE.clear();
		DIRTY_CHUNKS.clear();
		String worldKey = currentWorldKey;
		if (worldKey != null) {
			synchronized (SAVE_LOCK) {
				try {
					Files.deleteIfExists(saveFile(worldKey));
				} catch (IOException ignored) {
					// not critical — the file is simply overwritten by the next saveWorld()
				}
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

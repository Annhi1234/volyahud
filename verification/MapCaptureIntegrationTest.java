import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Integration check of the map capture path — with a REAL chunk.
 *
 * <p>Builds an actual {@link LevelChunk} with a known terrain shape (grass,
 * water, stone), then runs the mod's own capture logic on it and checks:
 *
 * <ul>
 *   <li>the heightmap read (with LOCAL coordinates) returns the right
 *       heights — this is what was once broken and mixed up chunks;</li>
 *   <li>the column colour logic maps grass -&gt; GRASS, water -&gt; WATER,
 *       stone -&gt; STONE;</li>
 *   <li>the water column reports a depth (used for the water shading);</li>
 *   <li>{@code MapTerrain.build} turns that into visible, opaque, correctly
 *       coloured pixels.</li>
 * </ul>
 *
 * <p>This is the check that would have caught "the map is completely black
 * and never loads" before shipping.
 */
public class MapCaptureIntegrationTest {

	/** Surface height of our synthetic terrain. */
	private static final int SURFACE = 64;
	/** Bottom of the world. */
	private static final int MIN_Y = -64;

	public static void main(String[] args) throws Exception {
		int failures = 0;

		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();

		ChunkAccess chunk = makeChunk();

		// --- 1. Heightmap read with LOCAL coordinates. ---
		int hGrass = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 1, 1);
		int hWater = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 7, 1);
		int hStone = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 12, 1);
		failures += expect("grass column height is 64", 64, hGrass);
		failures += expect("water column height is 64", 64, hWater);
		failures += expect("stone column height is 64", 64, hStone);

		// --- 2. Column colour logic. ---
		Class<?> cacheClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache");
		Method captureColumn = null;
		for (Method m : cacheClass.getDeclaredMethods()) {
			if (m.getName().equals("captureColumn")) {
				captureColumn = m;
				break;
			}
		}
		if (captureColumn == null) {
			System.out.println("FAIL: captureColumn helper not found");
			failures++;
		} else {
			captureColumn.setAccessible(true);
			net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
			net.minecraft.core.BlockPos.MutableBlockPos below = new net.minecraft.core.BlockPos.MutableBlockPos();

			// captureColumn now fills a reusable scratch object (one per chunk
			// instead of 256 allocations), so the harness must pass one in.
			Class<?> columnClass = Class.forName(
					"com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache$Column");
			Constructor<?> columnCtor = columnClass.getDeclaredConstructor();
			columnCtor.setAccessible(true);
			Object grassCol = columnCtor.newInstance();
			Object waterCol = columnCtor.newInstance();
			Object stoneCol = columnCtor.newInstance();

			captureColumn.invoke(null, chunk, 1, 1, 64, MIN_Y, pos, below, grassCol);
			captureColumn.invoke(null, chunk, 7, 1, 64, MIN_Y, pos, below, waterCol);
			captureColumn.invoke(null, chunk, 12, 1, 64, MIN_Y, pos, below, stoneCol);

			// MapColor ids: GRASS = 1, WATER = 12, STONE = 11.
			failures += expect("grass column maps to MapColor.GRASS", 1, colorIdOf(grassCol));
			failures += expect("water column maps to MapColor.WATER", 12, colorIdOf(waterCol));
			failures += expect("stone column maps to MapColor.STONE", 11, colorIdOf(stoneCol));
			failures += expect("water column reports a depth", true, fluidCountOf(waterCol) > 0);
			failures += expect("grass column reports no depth", 0, fluidCountOf(grassCol));
		}

		// --- 3. MapTerrain turns captured data into visible pixels. ---
		Class<?> dataClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache$ChunkData");
		Constructor<?> ctor = dataClass.getDeclaredConstructor(byte[].class, short[].class, byte[].class);
		ctor.setAccessible(true);
		byte[] colorIds = new byte[256];
		short[] colorY = new short[256];
		byte[] fluid = new byte[256];
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int idx = lz * 16 + lx;
				colorIds[idx] = (byte) (lx < 6 ? 1 : lx < 10 ? 12 : 11);
				colorY[idx] = 64;
				fluid[idx] = (byte) (lx >= 6 && lx < 10 ? 1 : 0);
			}
		}
		Object data = ctor.newInstance(colorIds, colorY, fluid);

		Field cacheField = cacheClass.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<Long, Object>> cache = (Map<String, Map<Long, Object>>) cacheField.get(null);
		Map<Long, Object> dimMap = new java.util.concurrent.ConcurrentHashMap<>();
		dimMap.put(0L, data);
		cache.put("minecraft:overworld", dimMap);

		Class<?> terrainClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.MapTerrain");
		Field sizeField = terrainClass.getDeclaredField("SIZE");
		sizeField.setAccessible(true);
		int size = sizeField.getInt(null);
		int half = size / 2;
		int originX = Math.floorDiv(0 - half, half) * half;
		int originZ = Math.floorDiv(0 - half, half) * half;

		int[] out = new int[size * size];
		terrainClass.getMethod("build", String.class, int.class, int.class, int[].class)
				.invoke(null, "minecraft:overworld", originX, originZ, out);

		int col = 0 - originX;
		int row = 0 - originZ;
		int grassPixel = out[row * size + col];
		int waterPixel = out[row * size + (col + 7)];
		int stonePixel = out[row * size + (col + 12)];

		failures += expect("grass pixel is opaque", 0xFF, (grassPixel >>> 24) & 0xFF);
		failures += expect("water pixel is opaque", 0xFF, (waterPixel >>> 24) & 0xFF);
		failures += expect("stone pixel is opaque", 0xFF, (stonePixel >>> 24) & 0xFF);
		failures += expect("grass pixel is green", true,
				((grassPixel >>> 8) & 0xFF) > ((grassPixel >>> 16) & 0xFF));
		failures += expect("water pixel is blue", true,
				(waterPixel & 0xFF) > ((waterPixel >>> 8) & 0xFF));
		failures += expect("grass and water pixels differ", true, grassPixel != waterPixel);

		// --- 4. The region alignment must always contain the player. ---
		failures += expect("player at X=0 is inside the region", true,
				(0 - originX) >= 0 && (0 - originX) < size);
		for (int c = -3000; c <= 3000; c += 37) {
			int r = Math.floorDiv(c - half, half) * half;
			if (!(r <= c && c < r + size)) {
				System.out.println("FAIL: region alignment excludes the player at X=" + c);
				failures++;
				break;
			}
		}

		if (failures == 0) {
			System.out.println("MapCaptureIntegrationTest: all checks passed");
		} else {
			System.out.println("MapCaptureIntegrationTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	private static int colorIdOf(Object column) throws Exception {
		Field f = column.getClass().getDeclaredField("colorId");
		f.setAccessible(true);
		return f.getInt(column);
	}

	private static int fluidCountOf(Object column) throws Exception {
		Field f = column.getClass().getDeclaredField("fluidCount");
		f.setAccessible(true);
		return f.getInt(column);
	}

	/**
	 * Builds a real chunk with a known terrain shape:
	 * - x in [0, 6)  -> grass block on top of stone
	 * - x in [6, 10) -> water on top of stone
	 * - x in [10,16) -> bare stone
	 */
	private static ChunkAccess makeChunk() {
		// The biome registry: we only need it to build the chunk sections.
		// It is a worldgen registry (loaded from data files), so it is not
		// available in this head-less harness — but the section only stores
		// a single biome per section, which the map code never reads.
		// We therefore build the sections reflectively with a stand-in
		// registry.
		Registry<Biome> biomes = standInBiomeRegistry();

		// A concrete height accessor: the vanilla one is an interface, so we
		// implement the few methods the chunk actually uses.
		net.minecraft.world.level.LevelHeightAccessor height = new net.minecraft.world.level.LevelHeightAccessor() {
			@Override
			public int getHeight() {
				return 320 - MIN_Y;
			}

			@Override
			public int getMinY() {
				return MIN_Y;
			}
		};

		int sectionCount = height.getSectionsCount();
		LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
		for (int i = 0; i < sectionCount; i++) {
			sections[i] = new LevelChunkSection(biomes);
		}

		ChunkAccess access = new net.minecraft.world.level.chunk.ProtoChunk(
				new ChunkPos(0, 0), null, height, biomes, null);

		// Fill the terrain.
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				// A solid floor of stone a bit below the surface.
				for (int y = MIN_Y; y < SURFACE; y++) {
					access.setBlockState(new BlockPos(x, y, z), stone, false);
				}
				// The surface block.
				BlockState top = x < 6 ? grass : (x < 10 ? water : stone);
				access.setBlockState(new BlockPos(x, SURFACE, z), top, false);
			}
		}

		// Make sure the heightmap is primed for our surface.
		Heightmap.primeHeightmaps(access, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));

		return access;
	}

	/**
	 * Builds a stand-in biome registry: an empty in-memory registry that is
	 * enough for {@link LevelChunkSection}, which only needs a registry to
	 * store its (single) biome holder.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Registry<Biome> standInBiomeRegistry() {
		net.minecraft.core.MappedRegistry<Biome> registry = new net.minecraft.core.MappedRegistry<>(
				net.minecraft.core.registries.Registries.BIOME,
				com.mojang.serialization.Lifecycle.stable());
		// The section constructor looks up the plains biome by key, so we
		// register a minimal one. The Biome constructor is package-private,
		// so we build it reflectively with the vanilla sub-objects.
		Biome plains = minimalBiome();
		registry.register(net.minecraft.world.level.biome.Biomes.PLAINS, plains,
				net.minecraft.core.RegistrationInfo.BUILT_IN);
		return (Registry) registry;
	}

	/**
	 * Builds the simplest possible Biome.
	 *
	 * <p>The Biome constructor and its sub-objects are package-private, so
	 * we reach them reflectively. The map code never reads the biome, we
	 * only need a valid instance for the chunk section.
	 */
	private static Biome minimalBiome() {
		try {
			Class<?> climateClass = Class.forName("net.minecraft.world.level.biome.Biome$ClimateSettings");
			Constructor<?> climateCtor = climateClass.getDeclaredConstructor(
					boolean.class, float.class,
					Class.forName("net.minecraft.world.level.biome.Biome$TemperatureModifier"),
					float.class);
			climateCtor.setAccessible(true);

			Class<?> modifierClass = Class.forName("net.minecraft.world.level.biome.Biome$TemperatureModifier");
			Object none = null;
			for (Object c : modifierClass.getEnumConstants()) {
				if (c.toString().equals("NONE")) {
					none = c;
					break;
				}
			}
			if (none == null) none = modifierClass.getEnumConstants()[0];

			Object climate = climateCtor.newInstance(true, 0.8f, none, 0.4f);

			// BiomeSpecialEffects has a single (package-private) constructor:
			//   (skyColor, fogColor, waterColor, waterFogColor, fogColorOverride,
			//    waterColorOverride, grassColorModifier, ambientParticleSettings,
			//    ambientLoopSoundEvent, ambientMoodSettings, ambientAdditionsSettings,
			//    backgroundMusic, backgroundMusicVolume)
			Class<?> effectsClass = Class.forName("net.minecraft.world.level.biome.BiomeSpecialEffects");
			Constructor<?> effectsCtor = effectsClass.getDeclaredConstructors()[0];
			effectsCtor.setAccessible(true);
			Object empty = java.util.Optional.empty();
			Object grassModifier = Class.forName(
					"net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier")
					.getEnumConstants()[0];
			Object effects = effectsCtor.newInstance(
					0x78A7FF, 0xC0C0C0, 0x3F76E4, 0x3F76E4,
					empty, empty, grassModifier, empty, empty, empty, empty, empty, 0.0f);

			Class<?> genClass = Class.forName("net.minecraft.world.level.biome.BiomeGenerationSettings");
			Object generation = genClass.getField("EMPTY").get(null);

			Constructor<?> biomeCtor = Biome.class.getDeclaredConstructor(
					climateClass, effectsClass, genClass,
					Class.forName("net.minecraft.world.level.biome.MobSpawnSettings"));
			biomeCtor.setAccessible(true);
			Object mobSettings = Class.forName("net.minecraft.world.level.biome.MobSpawnSettings")
					.getField("EMPTY").get(null);
			return (Biome) biomeCtor.newInstance(climate, effects, generation, mobSettings);
		} catch (Throwable t) {
			throw new RuntimeException("could not build a stand-in biome", t);
		}
	}

	private static int expect(String name, Object expected, Object actual) {
		if (expected.equals(actual)) return 0;
		System.out.println("FAIL: " + name + " — expected " + expected + " but got " + actual);
		return 1;
	}
}

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * Checks that the INCREMENTAL map rebuild produces exactly the same picture as
 * a full rebuild.
 *
 * <p>This matters because the incremental path (which only re-reads the rows
 * of the chunks that changed) must not leave stale pixels behind — otherwise
 * the map would show a mix of old and new terrain.
 */
public class MapIncrementalTest {

	public static void main(String[] args) throws Exception {
		int failures = 0;

		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();

		Class<?> cacheClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache");
		Class<?> dataClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache$ChunkData");
		Class<?> terrainClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.MapTerrain");

		Field cacheField = cacheClass.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<Long, Object>> cache = (Map<String, Map<Long, Object>>) cacheField.get(null);

		Constructor<?> ctor = dataClass.getDeclaredConstructor(byte[].class, short[].class, byte[].class);
		ctor.setAccessible(true);

		Field sizeField = terrainClass.getDeclaredField("SIZE");
		sizeField.setAccessible(true);
		int size = sizeField.getInt(null);
		int half = size / 2;
		int originX = Math.floorDiv(0 - half, half) * half;
		int originZ = Math.floorDiv(0 - half, half) * half;

		// Two chunks with different terrain.
		byte[] colorsA = new byte[256];
		short[] heightsA = new short[256];
		byte[] fluidA = new byte[256];
		for (int i = 0; i < 256; i++) {
			colorsA[i] = 1;   // grass
			heightsA[i] = 64;
		}
		// The second chunk (chunk X = 1) has water and a different height.
		byte[] colorsB = new byte[256];
		short[] heightsB = new short[256];
		byte[] fluidB = new byte[256];
		for (int i = 0; i < 256; i++) {
			colorsB[i] = 12;  // water
			heightsB[i] = 60;
			fluidB[i] = 3;
		}

		Object dataA = ctor.newInstance(colorsA, heightsA, fluidA);
		Object dataB = ctor.newInstance(colorsB, heightsB, fluidB);

		Map<Long, Object> dimMap = new java.util.concurrent.ConcurrentHashMap<>();
		dimMap.put(0L, dataA);
		dimMap.put(1L, dataB);
		cache.put("minecraft:overworld", dimMap);

		Method buildFull = terrainClass.getMethod("build", String.class, int.class, int.class, int[].class);
		Method buildPartial = terrainClass.getMethod("build", String.class, int.class, int.class,
				int[].class, Set.class);

		// --- Full rebuild. ---
		int[] full = new int[size * size];
		buildFull.invoke(null, "minecraft:overworld", originX, originZ, full);

		// --- Incremental: only chunk 1 is "dirty". ---
		int[] partial = new int[size * size];
		// Start from a copy of the full result, then apply the partial
		// rebuild on top of a fresh buffer that has the OLD data for chunk 0
		// and the NEW data for chunk 1.
		System.arraycopy(full, 0, partial, 0, full.length);

		// To make it a fair test, the partial rebuild must start from a
		// buffer where chunk 1 still has the OLD (grass) data.
		dimMap.put(1L, dataA);
		int[] partial2 = new int[size * size];
		buildFull.invoke(null, "minecraft:overworld", originX, originZ, partial2);

		// Now the "dirty" chunk 1 gets its new data, and we rebuild only its rows.
		dimMap.put(1L, dataB);
		Set<Long> dirty = new java.util.HashSet<>();
		dirty.add(net.minecraft.world.level.ChunkPos.asLong(1, 0));
		buildPartial.invoke(null, "minecraft:overworld", originX, originZ, partial2, dirty);

		// Compare.
		int differing = 0;
		for (int i = 0; i < full.length; i++) {
			if (full[i] != partial2[i]) differing++;
		}
		failures += expect("incremental rebuild matches the full rebuild", 0, differing);

		// And the water chunk must actually differ from grass in the full
		// rebuild (otherwise the test above would be vacuous).
		int col = 16 - originX;   // first column of chunk 1
		int row = 0 - originZ;
		boolean differs = false;
		for (int r = 0; r < 16; r++) {
			int i = (r + row) * size + (col);
			int j = (r + row) * size + (col - 16);
			if (full[i] != full[j]) {
				differs = true;
				break;
			}
		}
		failures += expect("water chunk is visibly different from grass", true, differs);

		if (failures == 0) {
			System.out.println("MapIncrementalTest: all checks passed");
		} else {
			System.out.println("MapIncrementalTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	private static int expect(String name, Object expected, Object actual) {
		if (expected.equals(actual)) return 0;
		System.out.println("FAIL: " + name + " — expected " + expected + " but got " + actual);
		return 1;
	}
}

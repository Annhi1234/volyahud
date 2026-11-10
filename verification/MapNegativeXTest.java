import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regression check for the negative-X bug in {@code MapTerrain.readRow}.
 *
 * <p>The old code used {@code start = Math.max(0, -worldX)} to find the offset
 * inside a chunk. For negative world X this produced a large positive number,
 * so the loop body never ran and the whole left half of the map (X &lt; 0)
 * stayed transparent/black.
 *
 * <p>This harness puts a fake chunk at chunk X = -1 and verifies that pixels
 * belonging to that chunk are actually coloured.
 */
public class MapNegativeXTest {

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

		// Flat grass chunk covering world X/Z in [-16, -1].
		byte[] colorIds = new byte[256];
		short[] colorY = new short[256];
		byte[] fluid = new byte[256];
		for (int i = 0; i < 256; i++) {
			colorIds[i] = 1; // MapColor.GRASS
			colorY[i] = 64;
		}
		Constructor<?> ctor = dataClass.getDeclaredConstructor(byte[].class, short[].class, byte[].class);
		ctor.setAccessible(true);
		Object data = ctor.newInstance(colorIds, colorY, fluid);

		Map<Long, Object> dimMap = new ConcurrentHashMap<>();
		dimMap.put(net.minecraft.world.level.ChunkPos.asLong(-1, -1), data);
		cache.put("minecraft:overworld", dimMap);

		Field sizeField = terrainClass.getDeclaredField("SIZE");
		sizeField.setAccessible(true);
		int size = sizeField.getInt(null);

		// Region containing the negative chunk entirely.
		int originX = -512;
		int originZ = -256;
		int[] out = new int[size * size];
		terrainClass.getMethod("build", String.class, int.class, int.class, int[].class)
				.invoke(null, "minecraft:overworld", originX, originZ, out);

		failures += expectOpaque(out, size, originX, originZ, -16, -16, "top-left of the negative chunk");
		failures += expectOpaque(out, size, originX, originZ, -1, -1, "bottom-right of the negative chunk");
		failures += expectOpaque(out, size, originX, originZ, -8, -8, "middle of the negative chunk");

		// A pixel just outside the chunk must stay transparent.
		int col = -17 - originX;
		int row = -8 - originZ;
		int outside = out[row * size + col];
		failures += expect("pixel outside the loaded chunk stays transparent", 0, outside >>> 24);

		if (failures == 0) {
			System.out.println("MapNegativeXTest: all checks passed");
		} else {
			System.out.println("MapNegativeXTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	private static int expectOpaque(int[] out, int size, int originX, int originZ,
			int worldX, int worldZ, String name) {
		int col = worldX - originX;
		int row = worldZ - originZ;
		if (col < 0 || col >= size || row < 0 || row >= size) {
			return expect(name + " is inside the image", true, false);
		}
		int argb = out[row * size + col];
		int alpha = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		int failures = expect(name + " is opaque", 0xFF, alpha);
		failures += expect(name + " is green (grass)", true, g > r && g > b);
		return failures;
	}

	private static int expect(String name, Object expected, Object actual) {
		if (expected.equals(actual)) return 0;
		System.out.println("FAIL: " + name + " — expected " + expected + " but got " + actual);
		return 1;
	}
}

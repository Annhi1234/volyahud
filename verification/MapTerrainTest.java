import java.lang.reflect.Field;
import java.util.Map;

/**
 * End-to-end check of the map terrain builder.
 *
 * <p>This harness feeds fake chunk data straight into {@code ChunkColorCache}
 * (via reflection, since the cache is normally filled from real chunks), then
 * calls {@code MapTerrain.build} and verifies the produced image:
 *
 * <ul>
 *   <li>the covered region actually contains the requested blocks (this is
 *       the bug that made the whole map black — the region used to be
 *       aligned so that the player fell outside of it);</li>
 *   <li>pixels are opaque and coloured (not transparent / not black);</li>
 *   <li>the hillshading reacts to a height step.</li>
 * </ul>
 */
public class MapTerrainTest {

	public static void main(String[] args) throws Exception {
		int failures = 0;

		// Minecraft's registries must be bootstrapped before we touch
		// ChunkPos (which is a Codec-backed record) or MapColor.
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();

		// --- Load the mod's classes (they live in the client source set). ---
		Class<?> cacheClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache");
		Class<?> dataClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache$ChunkData");
		Class<?> terrainClass = Class.forName("com.volyacraft.volyahud.client.hud.minimap.MapTerrain");

		Field cacheField = cacheClass.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Map<Long, Object>> cache = (Map<String, Map<Long, Object>>) cacheField.get(null);

		// --- Put a fake chunk of flat grass into the cache. ---
		// ChunkPos.asLong(0, 0) == 0.
		byte[] colorIds = new byte[256];
		short[] colorY = new short[256];
		byte[] fluid = new byte[256];
		for (int i = 0; i < 256; i++) {
			colorIds[i] = 1;   // MapColor.GRASS
			colorY[i] = 64;    // flat ground at Y=64
		}
		java.lang.reflect.Constructor<?> ctor = dataClass.getDeclaredConstructor(byte[].class, short[].class, byte[].class);
		ctor.setAccessible(true);
		Object data = ctor.newInstance(colorIds, colorY, fluid);

		Map<Long, Object> dimMap = new java.util.concurrent.ConcurrentHashMap<>();
		dimMap.put(0L, data);
		cache.put("minecraft:overworld", dimMap);

		// --- Build the image for a region that contains X=0. ---
		Field sizeField = terrainClass.getDeclaredField("SIZE");
		sizeField.setAccessible(true);
		int size = sizeField.getInt(null);

		// The region origin used by MapTexture for a player at X=0 is
		// floor((0 - SIZE/2) / (SIZE/2)) * (SIZE/2).
		int half = size / 2;
		int originX = Math.floorDiv(0 - half, half) * half;
		int originZ = Math.floorDiv(0 - half, half) * half;

		int[] out = new int[size * size];
		terrainClass.getMethod("build", String.class, int.class, int.class, int[].class)
				.invoke(null, "minecraft:overworld", originX, originZ, out);

		// --- Check the region contains the block we asked for. ---
		int col = 0 - originX;
		int row = 0 - originZ;
		boolean inRange = col >= 0 && col < size && row >= 0 && row < size;
		failures += expect("block (0,0) is inside the covered region", true, inRange);

		int argb = inRange ? out[row * size + col] : 0;
		int alpha = (argb >>> 24) & 0xFF;
		failures += expect("pixel (0,0) is opaque", 0xFF, alpha);

		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		failures += expect("pixel (0,0) is green (grass)", true, g > r && g > b);
		failures += expect("pixel (0,0) is not black", true, (r | g | b) > 0);

		// --- Count how many pixels ended up opaque: the whole covered chunk
		//     (256 blocks) plus the neighbouring chunk should be filled. ---
		int opaque = 0;
		for (int p : out) {
			if (((p >>> 24) & 0xFF) == 0xFF) opaque++;
		}
		failures += expect("at least 256 pixels are drawn", true, opaque >= 256);

		// --- Hillshading: a step in the height must change the brightness. ---
		// Build a chunk where column (0,0) is one block higher than (1,0).
		byte[] steppedColors = new byte[256];
		short[] steppedHeights = new short[256];
		byte[] steppedFluid = new byte[256];
		for (int i = 0; i < 256; i++) {
			steppedColors[i] = 1;
			steppedHeights[i] = 64;
		}
		// Vanilla shades along the Z axis (the neighbour towards -Z in the
		// same column), so the step must be along Z, not X.
		steppedHeights[1 * 16 + 0] = 65; // column at local z=1 is one higher
		Object stepped = ctor.newInstance(steppedColors, steppedHeights, steppedFluid);
		dimMap.put(0L, stepped);

		int[] out2 = new int[size * size];
		terrainClass.getMethod("build", String.class, int.class, int.class, int[].class)
				.invoke(null, "minecraft:overworld", originX, originZ, out2);

		int flatPixel = out2[row * size + col];
		int stepPixel = out2[(row + 1) * size + col];
		failures += expect("a height step changes the pixel brightness", true,
				flatPixel != stepPixel);

		if (failures == 0) {
			System.out.println("MapTerrainTest: all checks passed");
		} else {
			System.out.println("MapTerrainTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	private static int expect(String name, Object expected, Object actual) {
		if (expected.equals(actual)) return 0;
		System.out.println("FAIL: " + name + " — expected " + expected + " but got " + actual);
		return 1;
	}
}

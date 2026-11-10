

import net.minecraft.world.level.material.MapColor;

/**
 * Sanity checks for the vanilla map shading formula used by {@link MapTerrain}.
 *
 * <p>These are plain-JUnit-free checks (the mod has no test source set), so
 * they are written as a small self-contained harness that can be run with
 * {@code main}. They exist to lock in the exact vanilla behaviour that was
 * reverse-engineered from {@code MapItem#update} in Minecraft 1.21.4:
 *
 * <ul>
 *   <li>the land shading formula and its thresholds;</li>
 *   <li>the water shading formula and its thresholds;</li>
 *   <li>the parity dither term.</li>
 * </ul>
 */
public final class MapShadingTest {

	private MapShadingTest() {
	}

	/** The land formula, exactly as in {@link MapTerrain}. */
	static MapColor.Brightness landBrightness(int height, int previousHeight, int parity) {
		double shade = (height - previousHeight) * 4.0 / 5.0 + (parity - 0.5) * 0.4;
		if (shade > 0.6) return MapColor.Brightness.HIGH;
		if (shade < -0.6) return MapColor.Brightness.LOW;
		return MapColor.Brightness.NORMAL;
	}

	/** The water formula, exactly as in {@link MapTerrain}. */
	static MapColor.Brightness waterBrightness(int fluidCount, int parity) {
		double v = fluidCount * 0.1 + parity * 0.2;
		if (v < 0.5) return MapColor.Brightness.HIGH;
		if (v > 0.9) return MapColor.Brightness.LOW;
		return MapColor.Brightness.NORMAL;
	}

	public static void main(String[] args) {
		int failures = 0;

		// --- Land: a one block step is enough to change the brightness. ---
		failures += expect("flat ground, even parity is NORMAL",
				MapColor.Brightness.NORMAL, landBrightness(64, 64, 0));
		failures += expect("flat ground, odd parity is NORMAL",
				MapColor.Brightness.NORMAL, landBrightness(64, 64, 1));
		failures += expect("rising by 1 is HIGH",
				MapColor.Brightness.HIGH, landBrightness(65, 64, 0));
		failures += expect("rising by 1, odd parity is HIGH",
				MapColor.Brightness.HIGH, landBrightness(65, 64, 1));
		failures += expect("falling by 1 is LOW",
				MapColor.Brightness.LOW, landBrightness(63, 64, 0));
		failures += expect("falling by 1, odd parity is LOW",
				MapColor.Brightness.LOW, landBrightness(63, 64, 1));
		failures += expect("rising by 5 is HIGH",
				MapColor.Brightness.HIGH, landBrightness(69, 64, 0));
		failures += expect("falling by 5 is LOW",
				MapColor.Brightness.LOW, landBrightness(59, 64, 0));

		// --- Water: depth drives the brightness, the dither only nudges it. ---
		failures += expect("shallow water (1 deep) is HIGH",
				MapColor.Brightness.HIGH, waterBrightness(1, 0));
		failures += expect("shallow water (1 deep), odd parity is HIGH",
				MapColor.Brightness.HIGH, waterBrightness(1, 1));
		failures += expect("water 3 deep is NORMAL",
				MapColor.Brightness.NORMAL, waterBrightness(3, 1));
		failures += expect("water 5 deep is NORMAL",
				MapColor.Brightness.NORMAL, waterBrightness(5, 0));
		failures += expect("water 7 deep is LOW",
				MapColor.Brightness.LOW, waterBrightness(7, 1));
		failures += expect("water 10 deep is LOW",
				MapColor.Brightness.LOW, waterBrightness(10, 0));

		// --- The packed id layout vanilla uses. ---
		failures += expect("packed id combines colour and brightness",
				(MapColor.WATER.id << 2) | MapColor.Brightness.HIGH.id,
				MapColor.WATER.getPackedId(MapColor.Brightness.HIGH) & 0xFF);

		if (failures == 0) {
			System.out.println("MapShadingTest: all checks passed");
		} else {
			System.out.println("MapShadingTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	private static int expect(String name, Object expected, Object actual) {
		if (expected.equals(actual)) return 0;
		System.out.println("FAIL: " + name + " — expected " + expected + " but got " + actual);
		return 1;
	}
}

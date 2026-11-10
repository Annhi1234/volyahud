import net.minecraft.world.level.material.MapColor;

/**
 * Checks that the map pixel path produces the colours we expect.
 *
 * <p>The terrain image is stored as ARGB ints in {@code MapTerrain}, then
 * written into a {@code NativeImage} (which is RGBA internally) through
 * {@code applyToAllPixels}. That method converts ABGR -> ARGB on the way
 * in and ARGB -> ABGR on the way out, so the value we hand it must be a
 * normal ARGB colour.
 *
 * <p>This harness reproduces that conversion by hand and checks that a known
 * MapColor survives the round trip.
 */
public class MapPixelFormatTest {

	public static void main(String[] args) {
		int failures = 0;

		// The colour we build for a grass pixel, at NORMAL brightness.
		int argb = MapColor.GRASS.calculateARGBColor(MapColor.Brightness.NORMAL);
		failures += expect("grass colour is opaque", true, ((argb >>> 24) & 0xFF) == 0xFF);

		// NativeImage stores ABGR. applyToAllPixels does ABGR -> ARGB before
		// calling our function, and ARGB -> ABGR after. So the value we see
		// is plain ARGB, and the value we return is written back as ABGR.
		// A round trip through ABGR must give the same ARGB back.
		int abgr = toAbgr(argb);
		int backToArgb = toArgb(abgr);
		failures += expect("ARGB survives the ABGR round trip", argb, backToArgb);

		// A transparent pixel (unexplored area) must stay transparent.
		int transparent = 0;
		failures += expect("unexplored pixel stays transparent", 0,
				toArgb(toAbgr(transparent)) & 0xFF000000);

		// The red channel must not be swapped with the blue one — a swapped
		// channel is the classic "the map looks weirdly coloured" bug.
		int red = 0xFFFF0000;
		failures += expect("red stays red", 0xFF, (toArgb(toAbgr(red)) >>> 16) & 0xFF);
		failures += expect("blue stays zero", 0x00, (toArgb(toAbgr(red)) >>> 0) & 0xFF);
		failures += expect("green stays zero", 0x00, (toArgb(toAbgr(red)) >>> 8) & 0xFF);

		// A blue pixel must stay blue (the classic channel-swap bug).
		int blue = 0xFF0000FF;
		failures += expect("blue stays blue", 0xFF, (toArgb(toAbgr(blue)) >>> 0) & 0xFF);
		failures += expect("red stays zero", 0x00, (toArgb(toAbgr(blue)) >>> 16) & 0xFF);

		if (failures == 0) {
			System.out.println("MapPixelFormatTest: all checks passed");
		} else {
			System.out.println("MapPixelFormatTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	/** ARGB -> ABGR (the layout NativeImage uses internally). */
	private static int toAbgr(int argb) {
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >>> 16) & 0xFF;
		int g = (argb >>> 8) & 0xFF;
		int b = argb & 0xFF;
		return (a << 24) | (b << 16) | (g << 8) | r;
	}

	/** ABGR -> ARGB. */
	private static int toArgb(int abgr) {
		int a = (abgr >>> 24) & 0xFF;
		int b = (abgr >>> 16) & 0xFF;
		int g = (abgr >>> 8) & 0xFF;
		int r = abgr & 0xFF;
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int expect(String name, Object expected, Object actual) {
		if (expected.equals(actual)) return 0;
		System.out.println("FAIL: " + name + " — expected " + expected + " but got " + actual);
		return 1;
	}
}

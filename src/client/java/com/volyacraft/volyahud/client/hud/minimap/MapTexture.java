package com.volyacraft.volyahud.client.hud.minimap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Holds the terrain image of the map as a real GPU texture and keeps it in
 * sync with the explored chunks.
 *
 * <h2>How this works</h2>
 * The terrain is assembled once into a {@link NativeImage}, uploaded to a
 * {@link DynamicTexture} and then drawn as a single textured quad — the same
 * idea as the vanilla map item. Before, every block was drawn as its own
 * quad through the Tesselator, which meant tens of thousands of vertices per
 * frame (the source of the minimap lag).
 *
 * <h2>The covered region</h2>
 * The texture covers a world-ALIGNED square of {@link MapTerrain#SIZE} blocks
 * per side. Aligning it to the world (rather than to the player) means the
 * texture only has to be rebuilt when the player walks out of the region —
 * not on every step — so panning the fullscreen map stays smooth.
 */
public final class MapTexture {

	/**
	 * Локація, під якою реєструється текстура.
	 *
	 * <p>УВАЖНО: вона має бути УНІКАЛЬНОЮ для кожного екземпляра!
	 * {@code TextureManager#register} закриває (release) попередню текстуру
	 * під тим самим іменем — тобто якби мінімапа й повноекранна карта
	 * користувалися б однією локацією, то відкриття карти знищило б
	 * текстуру мінімапи (і навпаки). Тому кожен екземпляр має свою.
	 */
	private final ResourceLocation location;

	/** Лічильник для генерації унікальних імен текстур. */
	private static final java.util.concurrent.atomic.AtomicInteger TEXTURE_COUNTER =
			new java.util.concurrent.atomic.AtomicInteger();

	private final Minecraft mc;

	private DynamicTexture texture;
	private int[] pixels = new int[MapTerrain.SIZE * MapTerrain.SIZE];

	/**
	 * Власні буфери побудови (див. {@link MapTerrain.Buffers}). Спільні
	 * буфери між мінімапою й повноекранною картою ламали часткову
	 * перебудову, коли області цих двох текстур відрізнялися.
	 */
	private final MapTerrain.Buffers terrainBuffers = new MapTerrain.Buffers();

	/** World coordinates of the top-left corner of the covered region. */
	private int regionX = Integer.MIN_VALUE;
	private int regionZ = Integer.MIN_VALUE;
	private String regionDimension = null;
	private long lastBuildMs = 0L;

	/**
	 * Версія змін {@link ChunkColorCache}, яку ця текстура вже врахувала.
	 * Власна для кожної текстури: мінімапа й повноекранна карта більше не
	 * «з'їдають» зміни одна в одної.
	 */
	private long dirtySequence = 0L;

	/** Minimum interval between two rebuilds, in milliseconds. */
	private static final long REBUILD_INTERVAL_MS = 200L;

	public MapTexture(Minecraft mc) {
		this.mc = mc;
		this.location = ResourceLocation.withDefaultNamespace(
				"volyahud/world_map_" + TEXTURE_COUNTER.getAndIncrement());
	}

	/** The texture location the terrain image is registered under. */
	public ResourceLocation location() {
		return location;
	}

	/**
	 * Rebuilds the texture if the covered region has to change. Must be called
	 * on the render thread (it touches the GPU).
	 *
	 * @param dimensionKey dimension
	 * @param centerX      world block X the region should be centred on
	 * @param centerZ      world block Z the region should be centred on
	 */
	public void update(String dimensionKey, int centerX, int centerZ) {
		long now = System.currentTimeMillis();

		// БАГ-ФІКС (карта була повністю чорна й не прогружалась): область
		// текстури раніше вирівнювалася по сітці так, що гравець міг
		// опинитися ПОЗА її межами. Наприклад, при centerX = 100 область
		// починалася з -512 і покривала [-512, 0) — тобто ані гравця, ані
		// всього світу поруч у текстурі не було, і на екрані було суцільне
		// чорне тло.
		//
		// Тепер область вирівнюється так, що її центр завжди поруч із
		// точкою, навколо якої будується карта:
		//     початок = floor((центр - SIZE/2) / GRID) * GRID
		//
		// Раніше GRID був SIZE/2 (256), через що центр опинявся в другій
		// половині текстури — аж до самого краю. Для великої мінімапи
		// (320 px при 1 px/блок) це означало, що права/нижня частина
		// панелі виходила за межі текстури й малювалась чорною. Тепер
		// GRID = SIZE/8 (64): центр завжди лежить на відстані 256..320
		// блоків від лівого/верхнього краю, тобто текстура покриває всю
		// панель навіть у найбільшому розмірі.
		int half = MapTerrain.SIZE / 2;
		int grid = MapTerrain.SIZE / 8;
		int wantedX = Math.floorDiv(centerX - half, grid) * grid;
		int wantedZ = Math.floorDiv(centerZ - half, grid) * grid;

		boolean regionChanged = !dimensionKey.equals(regionDimension)
				|| wantedX != regionX || wantedZ != regionZ;

		// ОПТИМІЗАЦІЯ: замість того щоб перечитувати всі 262144 блоки
		// картинки, ми дізнаємось, які саме чанки змінилися, і перечитуємо
		// лише їхні рядки. Тому поява нового чанка (раз на 2 секунди під час
		// ходьби) більше НЕ викликає повну перебудову — а це було причиною
		// регулярних мікрофризів на слабких машинках.
		// Атомарний знімок: усе, що позначиться ПІСЛЯ нього, матиме більшу
		// версію й потрапить у наступне оновлення (курсор рухаємо лише після
		// успішної побудови).
		ChunkColorCache.DirtySnapshot snapshot =
				ChunkColorCache.dirtySnapshot(dimensionKey, dirtySequence);
		java.util.Set<Long> dirty = regionChanged
				? java.util.Collections.emptySet()
				: snapshot.chunks();

		if (!regionChanged && dirty.isEmpty()) return;
		// Throttle rebuilds so a burst of chunk loads cannot stall a frame.
		// Курсор версії НЕ рухаємо: зміни самі «дочекаються» наступного кадру.
		if (!regionChanged && now - lastBuildMs < REBUILD_INTERVAL_MS) return;

		build(dimensionKey, wantedX, wantedZ, now, regionChanged ? null : dirty);
		dirtySequence = snapshot.sequence();
	}

	/** Forces a rebuild on the next call to {@link #update}. */
	public void invalidate() {
		regionX = Integer.MIN_VALUE;
		regionZ = Integer.MIN_VALUE;
		regionDimension = null;
		lastBuildMs = 0L;
	}

	private void build(String dimensionKey, int originX, int originZ, long now,
			java.util.Set<Long> dirtyChunks) {
		MapTerrain.build(dimensionKey, originX, originZ, pixels, dirtyChunks, terrainBuffers);

		if (texture == null) {
			texture = new DynamicTexture(MapTerrain.SIZE, MapTerrain.SIZE, false);
			// Nearest filtering: a map should look like crisp blocks, not a
			// blurry smear. This is also what vanilla does for map items.
			texture.setFilter(false, false);
			mc.getTextureManager().register(location, texture);
		}

		NativeImage image = texture.getPixels();
		if (image != null) {
			// applyToAllPixels walks the pixels in order and hands us the
			// current ARGB value; we simply replace it with our own.
			final int[] source = pixels;
			final int[] counter = {0};
			image.applyToAllPixels(current -> counter[0] < source.length ? source[counter[0]++] : 0);
		}
		texture.upload();

		regionX = originX;
		regionZ = originZ;
		regionDimension = dimensionKey;
		lastBuildMs = now;
	}

	/**
	 * Малює частину текстури рельєфу як один текстурований квад.
	 *
	 * <p>Робимо це самі (а не через {@code GuiGraphics#blit}), бо нам потрібно
	 * одночасно керувати ТРИМА речами: прямокутником джерела в текстурі
	 * (який блок світу який піксель), розміром на екрані (на скільки
	 * «блоків» збільшити) та зміщенням. Ванільний {@code blit} має кілька
	 * перевантажень із різним порядком аргументів, тож власний квад —
	 * однозначніший і найпростіший варіант.
	 *
	 * @param destX   екранна координата лівого краю
	 * @param destY   екранна координата верхнього краю
	 * @param destW   ширина на екрані (px)
	 * @param destH   висота на екрані (px)
	 * @param srcX    лівий край ділянки в текстурі (у блоках)
	 * @param srcY    верхній край ділянки в текстурі (у блоках)
	 * @param srcW    ширина ділянки в текстурі (у блоках)
	 * @param srcH    висота ділянки в текстурі (у блоках)
	 */
	public void drawRegion(GuiGraphics graphics, int destX, int destY, int destW, int destH,
			int srcX, int srcY, int srcW, int srcH) {
		if (texture == null || destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;

		int size = MapTerrain.SIZE;

		// Обмежуємо ділянку межами текстури (коли видно край дослідженої
		// території). БАГ-ФІКС: раніше масштаб рахувався цілочисельним
		// destW/srcW і округлювався на КОЖНОМУ кроці — через це край
		// «обрізаної» ділянки зсувався на кілька пікселів і карта сіпалась
		// при панорамі. Тепер масштаб рахується один раз (дробовим), а
		// позиція/розмір обрізаної частини — з нього.
		int left = Math.max(srcX, 0);
		int top = Math.max(srcY, 0);
		int right = Math.min(srcX + srcW, size);
		int bottom = Math.min(srcY + srcH, size);
		if (right <= left || bottom <= top) return;

		double scaleX = destW / (double) srcW;
		double scaleY = destH / (double) srcH;
		int clippedDestX = destX + (int) Math.round((left - srcX) * scaleX);
		int clippedDestY = destY + (int) Math.round((top - srcY) * scaleY);
		int clippedDestW = (int) Math.round((right - left) * scaleX);
		int clippedDestH = (int) Math.round((bottom - top) * scaleY);
		if (clippedDestW <= 0 || clippedDestH <= 0) return;

		// Стандартний GUI-шлях малювання текстури. Порядок параметрів цього
		// перевантаження blit перевірено за байткодом ванільного
		// GuiGraphics#blit:
		//
		//   blit(function, texture,
		//         destX, destY,          <- куди на екрані
		//         uOffset, vOffset,      <- зсув у текстурі, у ТЕКСЕЛЯХ
		//         destW, destH,           <- розмір НА ЕКРАНІ
		//         srcW, srcH,             <- розмір ділянки в текстурі, у ТЕКСЕЛЯХ
		//         textureWidth, textureHeight,
		//         tintColor)              <- ARGB-колір, а НЕ lightmap!
		//
		// БАГ-ФІКС: раніше тут передавався LightTexture.FULL_BRIGHT
		// (0x00F000F0). Останній аргумент blit — це колір вершини, тому
		// альфа дорівнювала 0 і весь рельєф малювався повністю прозорим.
		// Звідси й «чорна» карта: було видно лише темний фон під нею.
		graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, location,
				clippedDestX, clippedDestY,
				(float) left, (float) top,
				clippedDestW, clippedDestH,
				right - left, bottom - top,
				size, size,
				0xFFFFFFFF);
	}

	/** Releases the GPU texture (called when the world changes). */
	public void release() {
		if (texture != null) {
			mc.getTextureManager().release(location);
			texture = null;
		}
		invalidate();
	}

	/** The colour of a single pixel of the built image, or 0 when unknown. */
	public int pixelAt(int x, int y) {
		if (x < 0 || y < 0 || x >= MapTerrain.SIZE || y >= MapTerrain.SIZE) return 0;
		return pixels[y * MapTerrain.SIZE + x];
	}

	/** Top-left world X of the covered region. */
	public int regionX() {
		return regionX;
	}

	/** Top-left world Z of the covered region. */
	public int regionZ() {
		return regionZ;
	}
}

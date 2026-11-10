package com.volyacraft.volyahud.client.hud.minimap;

import net.minecraft.world.level.material.MapColor;

/**
 * Builds the terrain image of the map using the AUTHENTIC vanilla algorithm.
 *
 * <h2>How a vanilla map is coloured</h2>
 * A vanilla map item stores one byte per pixel: a palette id plus a
 * "brightness". The brightness is what gives a vanilla map its distinctive
 * look — hills and valleys are picked out by a directional light rather than
 * by the block colour alone. The exact formula (from {@code MapItem#update}
 * in Minecraft 1.21.4, at map scale 1, i.e. one map pixel = one block) is:
 *
 * <pre>
 *   water:  v = fluidCount * 0.1 + parity * 0.2
 *           v &lt; 0.5  -&gt; HIGH (bright)
 *           v &gt; 0.9  -&gt; LOW  (dark)
 *           else       -&gt; NORMAL
 *
 *   land:   shade = (height - previousHeight) * 4 / 5
 *                   + (parity - 0.5) * 0.4
 *           shade &gt;  0.6 -&gt; HIGH (bright)
 *           shade &lt; -0.6 -&gt; LOW  (dark)
 *           else          -&gt; NORMAL
 * </pre>
 *
 * <p>{@code parity} is {@code (pixelX + pixelZ) &amp; 1} — a subtle
 * checkerboard that keeps perfectly flat ground from looking like a flat
 * slab of colour.
 *
 * <h2>Scan order matters</h2>
 * Vanilla scans the map <b>column by column</b>: the outer loop runs over X,
 * the inner loop over Z (increasing). The "previous height" used for the
 * shading is therefore the height of the neighbour one step towards
 * <b>−Z</b> in the same column, and it is reset to zero at the start of
 * every column. Reproducing that order exactly is what makes our relief look
 * like a real Minecraft map rather than an approximation of one.
 *
 * <h2>Why not just draw coloured quads?</h2>
 * The old implementation drew every block as its own quad through the
 * Tesselator — tens of thousands of vertices per frame, which showed up as
 * lag. Now the whole picture is assembled into one texture and drawn with a
 * single blit, exactly like vanilla does.
 */
public final class MapTerrain {

	private MapTerrain() {
	}

	/**
	 * Size of the terrain image in pixels (1 pixel = 1 block). A vanilla map
	 * is 128x128; we use a larger one so a fullscreen map covers more
	 * territory at once.
	 */
	public static final int SIZE = 512;

	/**
	 * Scratch buffers for building one image.
	 *
	 * <p>ВАЖЛИВО: вони НЕ мають бути спільними для мінімапи й повноекранної
	 * карти. Часткова перебудова (лише змінені рядки) спирається на те, що
	 * в буферах лежить ПОПЕРЕДНЯ картинка тієї самої області. Коли два
	 * {@link MapTexture} мали різні області й користувалися спільними
	 * буферами, один із них міг «домалювати» свої рядки поверх чужої
	 * картинки — на екрані з'являлися зсунуті смуги. Тепер кожна текстура
	 * має власний набір буферів.
	 */
	public static final class Buffers {
		private final int[] colorIds = new int[SIZE * SIZE];
		private final int[] colorY = new int[SIZE * SIZE];
		private final int[] fluid = new int[SIZE * SIZE];
		private final boolean[] hasData = new boolean[SIZE * SIZE];

		/**
		 * Висоти рядку ПЕРЕД краєм текстури (світовий Z = originZ - 1).
		 *
		 * <p>Потрібні для правильної світлотіні: перший рядок картинки
		 * порівнюється з висотою сусіднього рядку (як у ванілі), а не з нулем.
		 * Інакше верхня смуга карти була б аномально світлою (різниця
		 * висоти ~64 блоки дає максимальну яскравість).
		 */
		private final short[] rowAboveY = new short[SIZE];

		/** Чи є дані в рядку над краєм текстури (для кожного стовпчика). */
		private final boolean[] rowAboveHasData = new boolean[SIZE];
	}

	/**
	 * Спільні буфери для викликів без власного стану (тестові утиліти та
	 * разові побудови). Сам мод завжди передає буфери з {@link MapTexture}.
	 */
	private static final Buffers SHARED_BUFFERS = new Buffers();

	/**
	 * Builds the terrain image into {@code out}, which must hold
	 * {@link #SIZE} * {@link #SIZE} entries — one ARGB value per pixel.
	 *
	 * @param dimensionKey dimension
	 * @param originX      world block X of the top-left corner of the image
	 * @param originZ      world block Z of the top-left corner of the image
	 */
	public static void build(String dimensionKey, int originX, int originZ, int[] out) {
		build(dimensionKey, originX, originZ, out, null, SHARED_BUFFERS);
	}

	/**
	 * Побудовує картинку — частково або повністю.
	 *
	 * <p>ОПТИМІЗАЦІЯ (важливо для слабких машин): повна перебудова читає
	 * 512x512 = 262144 блоків. Але коли просто дошлий новий чанк, змінилися
	 * лише його 16 рядків (256 блоків). Тому ми перечитуємо лише ті рядки,
	 * які реально змінилися, а решту залишаємо як є.
	 *
	 * @param dirtyChunks набір «брудних» чанків (ключі {@code ChunkPos.asLong}),
	 *                   або null — тоді перебудовуємо все повністю
	 */
	public static void build(String dimensionKey, int originX, int originZ, int[] out,
			java.util.Set<Long> dirtyChunks) {
		build(dimensionKey, originX, originZ, out, dirtyChunks, SHARED_BUFFERS);
	}

	/**
	 * Same as {@link #build(String, int, int, int[], java.util.Set)}, but with
	 * caller-owned scratch buffers. A {@link MapTexture} must own its buffers
	 * so an incremental rebuild never mixes rows from another map region.
	 */
	public static void build(String dimensionKey, int originX, int originZ, int[] out,
			java.util.Set<Long> dirtyChunks, Buffers buffers) {
		final int[] COLOR_IDS = buffers.colorIds;
		final int[] COLOR_Y = buffers.colorY;
		final int[] FLUID = buffers.fluid;
		final boolean[] HAS_DATA = buffers.hasData;

		// --- Pass 1: read the raw data of every pixel from the chunk cache.
		// The image is indexed row-major (row * SIZE + col), where a row is a
		// line of blocks along X at a fixed Z.
		boolean fullRebuild = dirtyChunks == null;

		if (fullRebuild) {
			java.util.Arrays.fill(HAS_DATA, false);
			for (int row = 0; row < SIZE; row++) {
				readRow(dimensionKey, originX, originZ + row, row, buffers);
			}
		} else {
			for (int row : dirtyRows(dirtyChunks, originZ)) {
				readRow(dimensionKey, originX, originZ + row, row, buffers);
			}
		}

		// The row just above the top edge of the image — needed so the first
		// row is shaded against the real neighbour instead of against zero.
		// Перечитуємо його завжди (це лише 512 блоків).
		java.util.Arrays.fill(buffers.rowAboveHasData, false);
		java.util.Arrays.fill(buffers.rowAboveY, (short) 0);
		readRow(dimensionKey, originX, originZ - 1, -1, buffers);

		// --- Pass 2: colour the pixels.
		//
		// Vanilla's order is X outer / Z inner, and the "previous height"
		// is the neighbour towards -Z inside the same column. We reproduce
		// that exactly: for each column we walk the rows in order and keep
		// the height of the row we have just done.
		// При частковій перебудові фарбуємо лише змінені рядки. Але
		// світлотінь залежить від висоти рядка ВИЩЕ — тож для кожної
		// зміненої ділянки нам потрібен і рядок ПІД нею (він порівнюється
		// з уже новою висотою), інакше на межі зміненого чанка лишалася б
		// стара смуга світлотіні.
		boolean[] rowsToPaint = null;
		if (!fullRebuild) {
			rowsToPaint = new boolean[SIZE];
			for (int r : dirtyRows(dirtyChunks, originZ)) {
				rowsToPaint[r] = true;
				if (r + 1 < SIZE) rowsToPaint[r + 1] = true;
			}
		}

		for (int col = 0; col < SIZE; col++) {
			// The height of the row above the top edge of the image.
			int previousHeight = buffers.rowAboveY[col];
			// Чи є взагалі для чого порівнювати. В першому рядку (або
			// після ділянки без даних) порівнювати ні з чим — і тоді
			// перший піксель просто «рівний» (NORMAL), а не аномально
			// світлий. Ваніль тут робить те саме: його перший оброблений
			// стовпчик опиняється поза видимою частиною мапи, тож світлотінь
			// рахується завжди від реального сусіда.
			boolean hasPrevious = buffers.rowAboveHasData[col];

			for (int row = 0; row < SIZE; row++) {
				int idx = row * SIZE + col;
				int outIdx = idx;

				// При частковій перебудові не чіпаємо рядки, які не змінилися.
				if (rowsToPaint != null && !rowsToPaint[row]) {
					// Але висота попереднього стовпчика все одно потрібна
					// для наступного рядка — беремо її з буфера.
					previousHeight = COLOR_Y[idx];
					hasPrevious = HAS_DATA[idx]
							&& COLOR_IDS[idx] != MapColor.NONE.id;
					continue;
				}

				if (!HAS_DATA[idx] || COLOR_IDS[idx] == MapColor.NONE.id) {
					// No data here — leave it fully transparent so the dark
					// map background shows through (unexplored area).
					//
					// ВАЖЛИВО: висота попереднього стовпчика тут НЕ
					// оновлюється — інакше наступний рядок порівнявся б з
					// нулем (немає даних) і став аномально світлим. У ванілі
					// порожні ділянки теж не оновлюють попередню висоту.
					out[outIdx] = 0;
					hasPrevious = false;
					continue;
				}

				MapColor color = colorById(COLOR_IDS[idx]);
				if (color == null) color = MapColor.NONE;

				int height = COLOR_Y[idx];
				// Vanilla uses the map pixel indices for the parity term. At
				// scale 1 the map pixel index is the world coordinate minus
				// the map centre; the centre offset is even for our
				// world-aligned region, so the parity of the world
				// coordinates is the same.
				int parity = (col + row) & 1;

				MapColor.Brightness brightness;
				if (color == MapColor.WATER) {
					double v = FLUID[idx] * 0.1 + parity * 0.2;
					if (v < 0.5) brightness = MapColor.Brightness.HIGH;
					else if (v > 0.9) brightness = MapColor.Brightness.LOW;
					else brightness = MapColor.Brightness.NORMAL;
				} else {
					// Без сусіда для порівняння — рівна поверхня.
					double shade = hasPrevious
							? (height - previousHeight) * 4.0 / 5.0 + (parity - 0.5) * 0.4
							: (parity - 0.5) * 0.4;
					if (shade > 0.6) brightness = MapColor.Brightness.HIGH;
					else if (shade < -0.6) brightness = MapColor.Brightness.LOW;
					else brightness = MapColor.Brightness.NORMAL;
				}

				// The height is remembered even for water — vanilla updates
				// the previous height for water columns too, so shorelines
				// shade against the water surface.
				previousHeight = height;
				hasPrevious = true;

				out[outIdx] = color.calculateARGBColor(brightness);
			}
		}
	}

	/**
	 * Знаходить рядки картинки, які треба перечитати, за набором «брудних»
	 * чанків.
	 *
	 * <p>Кожен чанк — це 16 рядків картинки (16 блоків у висоту по Z).
	 * Тому один змінений чанк = 16 рядків, які треба перечитати й
	 * перефарбувати.
	 *
	 * @return номери рядків (0..SIZE-1), які треба перебудувати
	 */
	private static int[] dirtyRows(java.util.Set<Long> dirtyChunks, int originZ) {
		// Найкраще випадок: перечитуємо рядки, у яких є хоч один брудний
		// чанк. Щоб не робити 512 перевірок на кожен брудний чанк, групуємо
		// їх за рядками.
		boolean[] rowDirty = new boolean[SIZE];
		for (long key : dirtyChunks) {
			int chunkZ = (int) (key >> 32);
			// Рядок картинки, відповідний цьому чанку.
			int firstRow = (chunkZ * 16) - originZ;
			for (int r = firstRow; r < firstRow + 16; r++) {
				if (r >= 0 && r < SIZE) rowDirty[r] = true;
			}
		}

		int count = 0;
		for (int r = 0; r < SIZE; r++) {
			if (rowDirty[r]) count++;
		}
		int[] rows = new int[count];
		int i = 0;
		for (int r = 0; r < SIZE; r++) {
			if (rowDirty[r]) rows[i++] = r;
		}
		return rows;
	}

	/**
	 * Читає один рядок блоків (світові X від {@code originX} до
	 * {@code originX + SIZE}) у буфери.
	 *
	 * <p>БАГ-ФІКС: раніше тут було {@code start = Math.max(0, -worldX)}.
	 * Для від'ємних світових X це давало додатне (велике) значення, цикл
	 * не виконувався взагалі — тобто вся ліва половина світу (X &lt; 0)
	 * була порожньою/чорною на карті. Тепер зсув усередині чанка
	 * рахується від початку чанка, і Negative X обробляється так само,
	 * як додатний.
	 *
	 * @param row індекс рядка в зображенні, або -1 для рядка над верхнім
	 *            краєм (тоді дані йдуть у {@code buffers.rowAboveY})
	 */
	private static void readRow(String dimensionKey, int originX, int worldZ, int row,
			Buffers buffers) {
		int chunkZ = worldZ >> 4;
		int localZ = worldZ & 15;
		int rowBase = row < 0 ? -1 : row * SIZE;
		if (row >= 0) {
			// Рядок перечитується повністю: спершу прибираємо старі дані,
			// інакше стовпчики, яких більше немає в кеші, лишалися б
			// намальованими.
			java.util.Arrays.fill(buffers.hasData, rowBase, rowBase + SIZE, false);
		}

		// A row may span several chunks, so we walk it chunk by chunk.
		int endX = originX + SIZE;
		int worldX = originX;
		while (worldX < endX) {
			// For negative coordinates >> 4 is a floor division and << 4
			// restores the exact chunk origin, so this works on both sides
			// of zero.
			int chunkX = worldX >> 4;
			int chunkStartX = chunkX << 4;
			ChunkColorCache.ChunkData data = ChunkColorCache.get(dimensionKey, chunkX, chunkZ);
			if (data != null) {
				// Offset inside this chunk that corresponds to worldX. It is
				// zero for chunk-aligned origins, but stays correct even if
				// a caller passes an unaligned origin.
				int start = Math.max(0, worldX - chunkStartX);
				int end = Math.min(16, endX - chunkStartX);
				for (int lx = start; lx < end; lx++) {
					int col = chunkStartX - originX + lx;
					if (col < 0 || col >= SIZE) continue;
					int idx = localZ * 16 + lx;
					if (row < 0) {
						buffers.rowAboveY[col] = data.colorY[idx];
						buffers.rowAboveHasData[col] = true;
					} else {
						buffers.colorIds[rowBase + col] = data.colorIds[idx] & 0xFF;
						buffers.colorY[rowBase + col] = data.colorY[idx];
						buffers.fluid[rowBase + col] = data.fluidCount[idx] & 0xFF;
						buffers.hasData[rowBase + col] = true;
					}
				}
			}
			// Jump to the next chunk boundary.
			worldX = (chunkX + 1) << 4;
		}
	}

	/**
	 * Cache of id -> MapColor. An array avoids boxing and a HashMap lookup for
	 * every one of the 262 144 pixels on each rebuild.
	 */
	private static final MapColor[] COLOR_BY_ID = buildColorTable();

	private static MapColor[] buildColorTable() {
		MapColor[] table = new MapColor[64];
		for (int id = 0; id < table.length; id++) {
			try {
				table[id] = MapColor.byId(id);
			} catch (RuntimeException ignored) {
				// no such id in the palette
			}
		}
		return table;
	}

	static MapColor colorById(int id) {
		return id >= 0 && id < COLOR_BY_ID.length ? COLOR_BY_ID[id] : null;
	}
}

package com.volyacraft.volyahud.client.hud.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.hud.HudStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Невеличка мінімапа в кутку екрана.
 *
 * Показує ТІЛЬКИ рельєф (кольори блоків з {@link ChunkColorCache}) навколо
 * гравця — жодних мобів чи інших гравців тут немає й ніколи не буде, бо
 * дані для малювання беруться виключно зі знімків блоків, без сканування
 * сутностей.
 *
 * Коли гравець зараз під землею (у печері/шахті — точніше, коли над його
 * головою немає видимого неба, {@code level.canSeeSky(...)}), мінімапа
 * замість рельєфу малює суцільний чорний квадрат: "де я знаходжусь під
 * землею" рельєф поверхні показувати нема сенсу, і це також прибирає
 * будь-який ризик побачити щось схоже на "просвічування" печер/руди.
 */
public final class MinimapHud {

	private static int captureCooldown = 0;
	private static final int CAPTURE_INTERVAL_TICKS = 40; // ~2 секунди
	private static final int CAPTURE_RADIUS_CHUNKS = 3;

	private MinimapHud() {
	}

	/**
	 * ОПТИМІЗАЦІЯ (головне джерело лагів мінімапи): раніше {@code drawTerrain}
	 * повністю перебудовував список квадів рельєфу (перебір усіх видимих
	 * блоків + виділення нового {@code List<float[]>} з боксингом) на
	 * КОЖНОМУ рендер-кадрі — тобто до сотень разів на секунду на моніторах
	 * з високою частотою оновлення, хоча самі кольори рельєфу змінюються
	 * набагато рідше. При великому розмірі мінімапи й малому зумі це були
	 * сотні тисяч ітерацій та алокацій щосекунди — саме це відчувалось як
	 * "лаги з мінімапою".
	 * <p>
	 * Тепер перебудова прив'язана до ігрового тіку (максимум ~20 разів на
	 * секунду, а не до частоти кадрів): {@link #tick(Minecraft)} лише
	 * виставляє прапорець "потрібна перебудова", а сам рендер повторно
	 * використовує вже готовий буфер, поки прапорець не піднято. Затримка
	 * в один тік (~50мс) на мінімапі непомітна оку, а виграш у продуктивності —
	 * величезний, особливо при vsync=off/високій частоті монітора.
	 */
	private static boolean terrainDirty = true;

	/** Раз на CAPTURE_INTERVAL_TICKS сканує чанки навколо гравця й оновлює їхні кольори в кеші. */
	public static void tick(Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.minimap || mc.player == null || mc.level == null) return;

		// Гравець (найімовірніше) зрушив із місця за цей тік — рельєф мінімапи
		// потребує перерахунку, але не частіше одного разу на тік (див. коментар
		// до поля terrainDirty вище).
		terrainDirty = true;

		if (captureCooldown-- > 0) return;
		captureCooldown = CAPTURE_INTERVAL_TICKS;

		int pcx = Mth.floor(mc.player.getX()) >> 4;
		int pcz = Mth.floor(mc.player.getZ()) >> 4;

		for (int cx = pcx - CAPTURE_RADIUS_CHUNKS; cx <= pcx + CAPTURE_RADIUS_CHUNKS; cx++) {
			for (int cz = pcz - CAPTURE_RADIUS_CHUNKS; cz <= pcz + CAPTURE_RADIUS_CHUNKS; cz++) {
				if (!mc.level.hasChunk(cx, cz)) continue;
				LevelChunk chunk = mc.level.getChunk(cx, cz);
				ChunkColorCache.captureChunk(mc.level, chunk);
			}
		}
	}

	public static void render(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.minimap || mc.player == null || mc.level == null) return;

		int size = Mth.clamp(cfg.minimapSize, 64, 320);
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		boolean right = cfg.minimapCorner == HudConfig.ScreenCorner.TOP_RIGHT
				|| cfg.minimapCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;
		boolean bottom = cfg.minimapCorner == HudConfig.ScreenCorner.BOTTOM_LEFT
				|| cfg.minimapCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT;

		int x = right ? screenW - 8 - size : 8;
		int y = bottom ? screenH - 8 - size : 8;
		x += cfg.minimapOffsetX;
		y += cfg.minimapOffsetY;
		x = Mth.clamp(x, 0, Math.max(0, screenW - size));
		y = Mth.clamp(y, 0, Math.max(0, screenH - size));

		HudStyle.panel(graphics, x - 2, y - 2, x + size + 2, y + size + 2);

		BlockPos playerPos = mc.player.blockPosition();
		boolean underground = !mc.level.canSeeSky(playerPos);

		graphics.enableScissor(x, y, x + size, y + size);
		if (underground) {
			graphics.fill(x, y, x + size, y + size, 0xFF060606);
		} else {
			drawTerrain(graphics, mc, cfg, x, y, size);
		}
		// НОВА ФУНКЦІЯ: стрілки-індикатори по краю мінімапи для міток, які
		// зараз фізично не влазять у видиму область (замало наближення/задалеко) —
		// показуємо в якому напрямку йти, навіть якщо сама мітка не видно.
		// Малюється всередині того самого scissor'а, що й рельєф вище, тому
		// індикатор ніколи не "вилазить" за межі квадратної панелі мінімапи.
		if (cfg.minimapWaypointArrows) {
			drawOffscreenWaypointArrows(graphics, mc, cfg, x, y, size);
		}
		graphics.disableScissor();

		graphics.fill(x - 2, y + size + 1, x + size + 2, y + size + 2, 0x60FFFFFF);

		// нерухома позначка "N" зверху мінімапи — карта завжди орієнтована на північ,
		// не обертається за поглядом (це навмисно: так простіше й дешевше рахувати,
		// і збігається з тим, як зазвичай орієнтована мапа-предмет у ваніли).
		graphics.drawCenteredString(mc.font, "N", x + size / 2, y + 1, 0xFFE5E5E5);

		// Власна позиція гравця — завжди по центру мінімапи. ПОКРАЩЕННЯ: замість
		// статичної квадратної цятки (яка не давала жодного уявлення, куди
		// дивиться гравець) малюємо трикутник-стрілку, повернуту за напрямком
		// погляду — так само, як прийнято майже в усіх мінімапа-модах.
		drawFacingArrow(graphics, x + size / 2f, y + size / 2f, mc.player.getYRot());
	}

	/**
	 * Трикутна стрілка напрямку погляду. Малюється тим самим прийомом
	 * (Tesselator + POSITION_COLOR), що й рельєф вище — так само дешево,
	 * один маленький draw call.
	 */
	private static void drawFacingArrow(GuiGraphics graphics, float cx, float cy, float yawDeg) {
		// Ванільний yaw: 0 = південь (+Z), 90 = захід (-X). Напрямок "вперед"
		// у світових X/Z, а мінімапа орієнтована на північ вгору, тому формула
		// збігається з тим, як рахується зсув рельєфу відносно гравця вище.
		double yawRad = Math.toRadians(yawDeg);
		double dirX = -Math.sin(yawRad);
		double dirZ = Math.cos(yawRad);
		double perpX = -dirZ;
		double perpZ = dirX;

		float tipX = cx + (float) (dirX * 7);
		float tipY = cy + (float) (dirZ * 7);
		float leftX = cx - (float) (dirX * 4) + (float) (perpX * 4.5);
		float leftY = cy - (float) (dirZ * 4) + (float) (perpZ * 4.5);
		float rightX = cx - (float) (dirX * 4) - (float) (perpX * 4.5);
		float rightY = cy - (float) (dirZ * 4) - (float) (perpZ * 4.5);

		Matrix4f pose = graphics.pose().last().pose();
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		buffer.addVertex(pose, tipX, tipY, 0).setColor(1f, 0.35f, 0.35f, 1f);
		buffer.addVertex(pose, leftX, leftY, 0).setColor(1f, 1f, 1f, 1f);
		buffer.addVertex(pose, rightX, rightY, 0).setColor(1f, 1f, 1f, 1f);

		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	// --- кеш перебудованого буфера квадів рельєфу (див. коментар до terrainDirty) ---
	// Примітивний float[] замість List<float[]> — так само уникаємо боксингу й
	// повторних алокацій тисяч маленьких масивів: буфер лише переростає (ніколи
	// не зменшується) і повторно використовується між перебудовами.
	private static float[] quadBuf = new float[64 * 8];
	private static int quadFloats = 0; // скільки float зараз реально використано в quadBuf

	private static int cachedX = Integer.MIN_VALUE, cachedY = Integer.MIN_VALUE;
	private static int cachedSize = -1, cachedPxPerBlock = -1;
	private static String cachedDim = null;

	private static void drawTerrain(GuiGraphics graphics, Minecraft mc, HudConfig cfg, int x, int y, int size) {
		int pxPerBlock = Mth.clamp(cfg.minimapZoom, 1, 6);
		String dim = mc.level.dimension().location().toString();

		// Перебудовуємо важкий буфер квадів лише якщо гравець зрушив цього тіку
		// (terrainDirty) АБО помітно змінились параметри самої панелі (розмір
		// вікна, зум, кут/зсув мінімапи, вимір) — інакше просто перевикористовуємо
		// вже готовий буфер із минулого разу.
		boolean paramsChanged = x != cachedX || y != cachedY || size != cachedSize
				|| pxPerBlock != cachedPxPerBlock || !dim.equals(cachedDim);
		if (terrainDirty || paramsChanged) {
			rebuildTerrainBuffer(mc, x, y, size, pxPerBlock, dim);
			terrainDirty = false;
			cachedX = x; cachedY = y; cachedSize = size; cachedPxPerBlock = pxPerBlock; cachedDim = dim;
		}

		// нерозвідана територія (ще не захоплена в кеш) лишається просто фоном панелі —
		// нічого не малюємо поверх, тому там видно темний фон панелі.
		drawQuadBuffer(graphics);

		// Мітки гравця малюються окремо, щокадру (це лише кілька дешевих fill(),
		// а не перебір тисяч блоків) — тому завжди на актуальній позиції гравця,
		// навіть у ті кадри, коли рельєф ще не перебудовувався цього тіку.
		double px = mc.player.getX();
		double pz = mc.player.getZ();
		int blocksHalf = Math.max(8, size / (2 * pxPerBlock));
		float centerX = x + size / 2f;
		float centerY = y + size / 2f;
		for (HudConfig.Waypoint wp : cfg.waypointsFor(dim)) {
			double dx = wp.x - px;
			double dz = wp.z - pz;
			if (Math.abs(dx) > blocksHalf || Math.abs(dz) > blocksHalf) continue;
			int mx = Math.round(centerX + (float) (dx * pxPerBlock));
			int mz = Math.round(centerY + (float) (dz * pxPerBlock));
			int color = 0xFF000000 | wp.color;
			graphics.fill(mx - 1, mz - 3, mx + 1, mz + 3, color);
			graphics.fill(mx - 3, mz - 1, mx + 3, mz + 1, color);
		}
	}

	/** Важка частина: перебір видимих блоків і заповнення {@link #quadBuf} — викликається щонайбільше раз на тік. */
	private static void rebuildTerrainBuffer(Minecraft mc, int x, int y, int size, int pxPerBlock, String dim) {
		quadFloats = 0;

		int blocksHalf = Math.max(8, size / (2 * pxPerBlock));
		double px = mc.player.getX();
		double pz = mc.player.getZ();

		int cx0 = (Mth.floor(px) - blocksHalf - 16) >> 4;
		int cx1 = (Mth.floor(px) + blocksHalf + 16) >> 4;
		int cz0 = (Mth.floor(pz) - blocksHalf - 16) >> 4;
		int cz1 = (Mth.floor(pz) + blocksHalf + 16) >> 4;

		float centerX = x + size / 2f;
		float centerY = y + size / 2f;

		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				int[] colors = ChunkColorCache.getOrNull(dim, cx, cz);
				if (colors == null) continue;

				for (int lx = 0; lx < 16; lx++) {
					double wx = cx * 16 + lx;
					double dx = wx - px;
					if (dx < -blocksHalf - 1 || dx > blocksHalf + 1) continue;
					for (int lz = 0; lz < 16; lz++) {
						double wz = cz * 16 + lz;
						double dz = wz - pz;
						if (dz < -blocksHalf - 1 || dz > blocksHalf + 1) continue;

						int argb = colors[lz * 16 + lx];
						float sx = centerX + (float) (dx * pxPerBlock);
						float sy = centerY + (float) (dz * pxPerBlock);
						pushQuad(sx, sy, sx + pxPerBlock, sy + pxPerBlock, argb);
					}
				}
			}
		}
	}

	private static void ensureQuadCapacity(int neededFloats) {
		if (quadBuf.length < neededFloats) {
			int newLen = quadBuf.length;
			while (newLen < neededFloats) newLen *= 2;
			quadBuf = java.util.Arrays.copyOf(quadBuf, newLen);
		}
	}

	private static void pushQuad(float x1, float y1, float x2, float y2, int argb) {
		ensureQuadCapacity(quadFloats + 8);
		float a = ((argb >>> 24) & 0xFF) / 255f;
		float r = ((argb >>> 16) & 0xFF) / 255f;
		float g = ((argb >>> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		quadBuf[quadFloats] = x1;
		quadBuf[quadFloats + 1] = y1;
		quadBuf[quadFloats + 2] = x2;
		quadBuf[quadFloats + 3] = y2;
		quadBuf[quadFloats + 4] = r;
		quadBuf[quadFloats + 5] = g;
		quadBuf[quadFloats + 6] = b;
		quadBuf[quadFloats + 7] = a;
		quadFloats += 8;
	}

	/** Малює закешований буфер квадів рельєфу одним draw call'ом (той самий прийом, що й раніше — лише без per-frame перебудови). */
	private static void drawQuadBuffer(GuiGraphics graphics) {
		if (quadFloats == 0) return;

		Matrix4f pose = graphics.pose().last().pose();
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

		for (int i = 0; i < quadFloats; i += 8) {
			float x1 = quadBuf[i], y1 = quadBuf[i + 1], x2 = quadBuf[i + 2], y2 = quadBuf[i + 3];
			float r = quadBuf[i + 4], g = quadBuf[i + 5], b = quadBuf[i + 6], a = quadBuf[i + 7];
			buffer.addVertex(pose, x1, y1, 0).setColor(r, g, b, a);
			buffer.addVertex(pose, x2, y1, 0).setColor(r, g, b, a);
			buffer.addVertex(pose, x2, y2, 0).setColor(r, g, b, a);
			buffer.addVertex(pose, x1, y2, 0).setColor(r, g, b, a);
		}

		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	/**
	 * Для кожної мітки поточного виміру, яка зараз ЗА межами видимого вікна
	 * мінімапи (за той самий blocksHalf, що й у drawTerrain — тому "видно
	 * напряму" тут і там рахується однаково), малює маленький трикутник на
	 * краю квадратної панелі в бік мітки, той самий прийом (Tesselator +
	 * TRIANGLES), що вже перевірений у drawFacingArrow вище.
	 */
	private static void drawOffscreenWaypointArrows(GuiGraphics graphics, Minecraft mc, HudConfig cfg, int x, int y, int size) {
		String dim = mc.level.dimension().location().toString();
		List<HudConfig.Waypoint> list = cfg.waypointsFor(dim);
		if (list.isEmpty()) return;

		int pxPerBlock = Mth.clamp(cfg.minimapZoom, 1, 6);
		int blocksHalf = Math.max(8, size / (2 * pxPerBlock));

		double px = mc.player.getX();
		double pz = mc.player.getZ();
		float centerX = x + size / 2f;
		float centerY = y + size / 2f;
		float edge = size / 2f - 6;

		for (HudConfig.Waypoint wp : list) {
			double dx = wp.x - px;
			double dz = wp.z - pz;
			if (Math.abs(dx) <= blocksHalf && Math.abs(dz) <= blocksHalf) continue; // вже видно напряму на рельєфі

			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist < 0.001) continue;
			double dirX = dx / dist;
			double dirZ = dz / dist;

			float ix = centerX + (float) (dirX * edge);
			float iy = centerY + (float) (dirZ * edge);
			drawArrowIndicator(graphics, ix, iy, dirX, dirZ, 0xFF000000 | wp.color);
		}
	}

	/** Трикутник-стрілка довільного кольору в довільній точці — той самий прийом, що й drawFacingArrow, але без прив'язки до погляду гравця. */
	private static void drawArrowIndicator(GuiGraphics graphics, float cx, float cy, double dirX, double dirZ, int argbColor) {
		double perpX = -dirZ;
		double perpZ = dirX;

		float tipX = cx + (float) (dirX * 5);
		float tipY = cy + (float) (dirZ * 5);
		float leftX = cx - (float) (dirX * 3) + (float) (perpX * 3.5);
		float leftY = cy - (float) (dirZ * 3) + (float) (perpZ * 3.5);
		float rightX = cx - (float) (dirX * 3) - (float) (perpX * 3.5);
		float rightY = cy - (float) (dirZ * 3) - (float) (perpZ * 3.5);

		float a = ((argbColor >>> 24) & 0xFF) / 255f;
		float r = ((argbColor >>> 16) & 0xFF) / 255f;
		float g = ((argbColor >>> 8) & 0xFF) / 255f;
		float b = (argbColor & 0xFF) / 255f;

		Matrix4f pose = graphics.pose().last().pose();
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		buffer.addVertex(pose, tipX, tipY, 0).setColor(r, g, b, a);
		buffer.addVertex(pose, leftX, leftY, 0).setColor(r, g, b, a);
		buffer.addVertex(pose, rightX, rightY, 0).setColor(r, g, b, a);

		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

}

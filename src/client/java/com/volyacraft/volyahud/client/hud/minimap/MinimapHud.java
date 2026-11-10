package com.volyacraft.volyahud.client.hud.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.VolyaHudClient;
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
 *
 * <h2>ОПТИМІЗАЦІЯ</h2>
 * Раніше рельєф малювався як тисячі окремих квадів через Tesselator — це
 * десятки тисяч вершин на КАЖЕН кадр, що і відчувалось як "лаги з
 * мінімапою" (особливо на моніторах з високою частотою оновлення, де
 * кадрів до сотні на секунду).
 *
 * Тепер рельєф збирається один раз у справжню текстуру
 * ({@link MapTexture}) тим самим ванільним алгоритмом, що й у мапи-предмета
 * (світлотінь + палітра {@link net.minecraft.world.level.material.MapColor}),
 * і малюється ОДНИМ викликом blit — як робить сама гра.
 */
public final class MinimapHud {

	private static int captureCooldown = 0;
	private static final int CAPTURE_INTERVAL_TICKS = 40; // ~2 секунди
	private static final int CAPTURE_RADIUS_CHUNKS = 3;

	/** Текстура рельєфу — переживає переходи між кадрами. */
	private static MapTexture mapTexture;

	/**
	 * Скидає кешовану текстуру мінімапи. Потрібно після очищення карти:
	 * інакше вже завантажена текстура продовжує показувати старий рельєф,
	 * доки гравець не відійде достатньо далеко або не змінить вимір.
	 */
	public static void invalidateMapTexture() {
		if (mapTexture != null) {
			mapTexture.invalidate();
		}
		cachedSize = -1;
		cachedZoom = -1;
		cachedDim = null;
	}
	/** Останній розмір панелі, за яким будувалася текстура. */
	private static int cachedSize = -1;
	private static int cachedZoom = -1;
	private static String cachedDim = null;

	/** Кешований ключ виміру (див. currentDimensionKey). */
	private static String cachedDimensionKey = null;
	private static Object cachedDimension = null;
	/** Кешований ключ світу/сервера — не створюємо новий String на кожен кадр. */
	private static String cachedWorldKey = null;
	private static Object cachedServer = null;
	private static Object cachedSingleplayer = null;

	/**
	 * Ключ виміру без алокації нового String на кожен кадр.
	 */
	private static String currentDimensionKey(Minecraft mc) {
		Object dimension = mc.level.dimension();
		if (dimension != cachedDimension) {
			cachedDimension = dimension;
			// ResourceKey#toString() дає "ResourceKey[minecraft:dimension / minecraft:overworld]",
			// а не "minecraft:overworld". Через це мінімапа шукала чанки під
			// неіснуючим ключем і завжди була порожньою. Беремо саме id виміру.
			cachedDimensionKey = mc.level.dimension().location().toString();
		}
		return cachedDimensionKey;
	}

	/** Ключ світу/сервера з кешем: адреса сервера чи назва одиночного світу. */
	private static String currentWorldKey(Minecraft mc) {
		Object server = mc.getCurrentServer();
		Object singleplayer = mc.getSingleplayerServer();
		if (server != cachedServer || singleplayer != cachedSingleplayer) {
			cachedServer = server;
			cachedSingleplayer = singleplayer;
			cachedWorldKey = VolyaHudClient.currentWorldKey(mc);
		}
		return cachedWorldKey;
	}

	private MinimapHud() {
	}

	/**
	 * Звільняє GPU-текстуру рельєфу — викликається при виході зі світу, щоб
	 * не тримати текстуру (і пам'ять під неї) між іграми.
	 */
	public static void releaseTexture() {
		if (mapTexture != null) {
			mapTexture.release();
			mapTexture = null;
		}
		cachedSize = -1;
		cachedZoom = -1;
		cachedDim = null;
		// Після від'єднання наступний світ може мати інший вимір/сервер.
		// Без скидання цих кешів MinimapHud міг показати старий ключ.
		cachedDimensionKey = null;
		cachedDimension = null;
		cachedWorldKey = null;
		cachedServer = null;
		cachedSingleplayer = null;
	}

	/**
	 * Раз на CAPTURE_INTERVAL_TICKS сканує чанки навколо гравця й оновлює їхні
	 * кольори в кеші.
	 *
	 * <p>Важливо: це працює навіть із вимкненою мінімапою, бо той самий кеш
	 * використовує повноекранна карта. Інакше вона перестала б бачити зміни
	 * блоків у вже завантажених чанках.
	 */
	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;

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

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		// На дуже великому GUI Scale екран може бути меншим за максимальний
		// розмір мінімапи. Обмежуємо панель по коротшій стороні, щоб вона не
		// вилазила за екран і N-бейдж не опинявся за кадром.
		int maxSize = Math.max(32, Math.min(screenW, screenH) - 16);
		int size = Math.min(Mth.clamp(cfg.minimapSize, 64, 320), maxSize);

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
		// Не ховаємо рельєф під будь-яким дахом (дерево, будинок) і в
		// Незері/Енді, де canSeeSky майже завжди false через стелю. Темний
		// фон лишаємо лише для справжніх печер у звичайному світі.
		boolean underground = !mc.level.dimensionType().hasCeiling()
				&& playerPos.getY() < mc.level.getSeaLevel()
				&& !mc.level.canSeeSky(playerPos);

		graphics.enableScissor(x, y, x + size, y + size);
		if (underground) {
			// Темно-сірий, а не чорний: під землею мінімапа не має
			// виглядати «діркою» в інтерфейсі.
			graphics.fill(x, y, x + size, y + size, 0xFF24262B);
		} else {
			drawTerrain(graphics, mc, cfg, x, y, size);
		}

		// Легка віньєтка по краях: рельєф не "обривається" об рамку, а
		// плавно затемнюється до неї — панель виглядає глибшою й охайнішою.
		graphics.fillGradient(x, y, x + size, y + 5, 0x48000000, 0x00000000);
		graphics.fillGradient(x, y + size - 5, x + size, y + size, 0x00000000, 0x58000000);
		graphics.fillGradient(x, y, x + 5, y + size, 0x38000000, 0x00000000);
		graphics.fillGradient(x + size - 5, y, x + size, y + size, 0x00000000, 0x38000000);

		// НОВА ФУНКЦІЯ: стрілки-індикатори по краю мінімапи для міток, які
		// зараз фізично не влазять у видиму область (замало наближення/задалеко) —
		// показуємо в якому напрямку йти, навіть якщо сама мітка не видно.
		// Малюється всередині того самого scissor'а, що й рельєф вище, тому
		// індикатор ніколи не "вилазить" за межі квадратної панелі мінімапи.
		if (cfg.minimapWaypointArrows) {
			drawOffscreenWaypointArrows(graphics, mc, cfg, x, y, size);
		}
		graphics.disableScissor();

		// Власна позиція гравця — завжди по центру мінімапи. ПОКРАЩЕННЯ: замість
		// статичної квадратної цятки (яка не давала жодного уявлення, куди
		// дивиться гравець) малюємо трикутник-стрілку, повернуту за напрямком
		// погляду — так само, як прийнято майже в усіх мінімапа-модах.
		drawFacingArrow(graphics, x + size / 2f, y + size / 2f, mc.player.getYRot());

		// нерухома позначка "N" зверху мінімапи — карта завжди орієнтована на північ,
		// не обертається за поглядом (це навмисно: так простіше й дешевше рахувати,
		// і збігається з тим, як зазвичай орієнтована мапа-предмет у ваніли).
		int northX = x + size / 2;
		HudStyle.badge(graphics, northX - 4, y - 1, northX + 5, y + 9, HudStyle.ACCENT_CYAN);
		graphics.drawCenteredString(mc.font, "N", northX, y, 0xFFF4F6F8);

		// Акцентні кути рамки — завершальний штрих, який робить мінімапу
		// схожою на "прилад", а не просто на квадрат з текстурою.
		HudStyle.corners(graphics, x - 2, y - 2, x + size + 2, y + size + 2, 7,
				HudStyle.withAlpha(0xFFFFFFFF, 0.55f));
	}

	/**
	 * Трикутна стрілка напрямку погляду. Малюється тим самим прийомом
	 * (Tesselator + POSITION_COLOR), що й раніше — так само дешево, один
	 * маленький draw call.
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

		// Темний контур навколо стрілки: на світлому снігу/піску біла
		// стрілка інакше губилася б.
		float oTipX = cx + (float) (dirX * 8.5);
		float oTipY = cy + (float) (dirZ * 8.5);
		float oLeftX = cx - (float) (dirX * 5.5) + (float) (perpX * 6.2);
		float oLeftY = cy - (float) (dirZ * 5.5) + (float) (perpZ * 6.2);
		float oRightX = cx - (float) (dirX * 5.5) - (float) (perpX * 6.2);
		float oRightY = cy - (float) (dirZ * 5.5) - (float) (perpZ * 6.2);

		Matrix4f pose = graphics.pose().last().pose();
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		buffer.addVertex(pose, oTipX, oTipY, 0).setColor(0f, 0f, 0f, 0.85f);
		buffer.addVertex(pose, oLeftX, oLeftY, 0).setColor(0f, 0f, 0f, 0.85f);
		buffer.addVertex(pose, oRightX, oRightY, 0).setColor(0f, 0f, 0f, 0.85f);
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

	// --- текстура рельєфу ---

	/**
	 * Малює рельєф мінімапи з готової текстури (одним blit). Текстура
	 * перебудовується лише тоді, коли змінився розмір панелі, зум, вимір або
	 * гравець вийшов за межі покритої текстурою — інакше вона просто
	 * перевикористовується.
	 */
	private static void drawTerrain(GuiGraphics graphics, Minecraft mc, HudConfig cfg, int x, int y, int size) {
		int pxPerBlock = Mth.clamp(cfg.minimapZoom, 1, 6);
		// ОПТИМІЗАЦІЯ: dimension().location().toString() створював новий
		// String на КОЖЕН кадр (двічі — тут і в drawOffscreenWaypointArrows).
		// На слабкому ПК це зайва робота в гарячому шляху, тож кешуємо
		// ключ виміру (він змінюється лише при переході в інший вимір).
		String dim = currentDimensionKey(mc);

		if (mapTexture == null) {
			mapTexture = new MapTexture(mc);
		}

		boolean paramsChanged = size != cachedSize || pxPerBlock != cachedZoom || !dim.equals(cachedDim);
		if (paramsChanged) {
			mapTexture.invalidate();
			cachedSize = size;
			cachedZoom = pxPerBlock;
			cachedDim = dim;
		}

		int playerX = Mth.floor(mc.player.getX());
		int playerZ = Mth.floor(mc.player.getZ());
		mapTexture.update(dim, playerX, playerZ);

		// Текстура покриває квадрат блоків, вирівняний по світу. Малюємо з
		// неї ту частину, що відповідає видимій області мінімапи.
		int regionX = mapTexture.regionX();
		int regionZ = mapTexture.regionZ();

		// Лівий верхній кут видимої області мінімапи у світових координатах.
		// Гравець стоїть точно по центру панелі.
		//
		// Кількість блоків, що вміщається в панель, рахуємо саме так, щоб
		// масштаб був ТОЧНО «pxPerBlock пікселів на блок» (без «+/- блок»,
		// через що раніше карта була злегка розмита й показувала зайвий
		// блок збоку).
		int blocksWide = (size + pxPerBlock - 1) / pxPerBlock;
		int worldLeft = playerX - blocksWide / 2;
		int worldTop = playerZ - blocksWide / 2;

		// Скільки блоків (і, відповідно, пікселів текстури) ми беремо.
		int srcW = blocksWide + 1;
		int srcH = srcW;

		int srcX = worldLeft - regionX;
		int srcZ = worldTop - regionZ;

		// Малюємо ділянку текстури як один текстурований квад. Розмір на
		// екрані — srcW * pxPerBlock, щоб кожен блок був рівно pxPerBlock
		// пікселів (і краї панелі не «обрізалися»).
		mapTexture.drawRegion(graphics, x, y, srcW * pxPerBlock, srcH * pxPerBlock,
				srcX, srcZ, srcW, srcH);

		// Мітки — справжніми ванільними спрайтами маркерів, як у грі.
		MapDecorations.render(graphics, mc, dim, currentWorldKey(mc),
				worldLeft, worldTop, pxPerBlock, x, y, size, size);
	}

	/**
	 * Для кожної мітки поточного виміру, яка зараз ЗА межами видимого вікна
	 * мінімапи, малює маленький трикутник на краю квадратної панелі в бік
	 * мітки, той самий прийом (Tesselator + TRIANGLES), що вже перевірений у
	 * drawFacingArrow вище.
	 */
	private static void drawOffscreenWaypointArrows(GuiGraphics graphics, Minecraft mc, HudConfig cfg, int x, int y, int size) {
		String dim = currentDimensionKey(mc);
		String world = currentWorldKey(mc);
		List<HudConfig.Waypoint> list = cfg.waypointsFor(dim);
		if (list.isEmpty()) return;

		int pxPerBlock = Mth.clamp(cfg.minimapZoom, 1, 6);
		// Точні пів-розміри видимої області в БЛОКАХ (а не в чанках): раніше
		// тут використовувалось blocksWide / 2, через що стрілка могла
		// з'являтися на 1 блок раніше/пізніше, ніж мітка реально виходила
		// за край мінімапи.
		double halfBlocks = size / (2.0 * pxPerBlock);

		double px = mc.player.getX();
		double pz = mc.player.getZ();
		float centerX = x + size / 2f;
		float centerY = y + size / 2f;
		float edge = size / 2f - 6;

		for (HudConfig.Waypoint wp : list) {
			if (!cfg.isWaypointVisible(wp, world)) continue;
			double dx = wp.x - px;
			double dz = wp.z - pz;
			if (Math.abs(dx) <= halfBlocks && Math.abs(dz) <= halfBlocks) continue; // вже видно напряму на рельєфі

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

		float oTipX = cx + (float) (dirX * 6.5);
		float oTipY = cy + (float) (dirZ * 6.5);
		float oLeftX = cx - (float) (dirX * 4) + (float) (perpX * 4.8);
		float oLeftY = cy - (float) (dirZ * 4) + (float) (perpZ * 4.8);
		float oRightX = cx - (float) (dirX * 4) - (float) (perpX * 4.8);
		float oRightY = cy - (float) (dirZ * 4) - (float) (perpZ * 4.8);

		float a = ((argbColor >>> 24) & 0xFF) / 255f;
		float r = ((argbColor >>> 16) & 0xFF) / 255f;
		float g = ((argbColor >>> 8) & 0xFF) / 255f;
		float b = (argbColor & 0xFF) / 255f;

		Matrix4f pose = graphics.pose().last().pose();
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		buffer.addVertex(pose, oTipX, oTipY, 0).setColor(0f, 0f, 0f, 0.8f);
		buffer.addVertex(pose, oLeftX, oLeftY, 0).setColor(0f, 0f, 0f, 0.8f);
		buffer.addVertex(pose, oRightX, oRightY, 0).setColor(0f, 0f, 0f, 0.8f);
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

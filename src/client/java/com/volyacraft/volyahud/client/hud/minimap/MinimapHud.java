package com.volyacraft.volyahud.client.hud.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix4f;

import java.util.ArrayList;
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

	/** Раз на CAPTURE_INTERVAL_TICKS сканує чанки навколо гравця й оновлює їхні кольори в кеші. */
	public static void tick(Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.minimap || mc.player == null || mc.level == null) return;
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

		graphics.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xB0000000);
		graphics.fill(x - 2, y - 2, x + size + 2, y - 1, 0x60FFFFFF);

		BlockPos playerPos = mc.player.blockPosition();
		boolean underground = !mc.level.canSeeSky(playerPos);

		graphics.enableScissor(x, y, x + size, y + size);
		if (underground) {
			graphics.fill(x, y, x + size, y + size, 0xFF060606);
		} else {
			drawTerrain(graphics, mc, cfg, x, y, size);
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

	private static void drawTerrain(GuiGraphics graphics, Minecraft mc, HudConfig cfg, int x, int y, int size) {
		int pxPerBlock = Mth.clamp(cfg.minimapZoom, 1, 6);
		int blocksHalf = Math.max(8, size / (2 * pxPerBlock));

		double px = mc.player.getX();
		double pz = mc.player.getZ();
		String dim = mc.level.dimension().location().toString();

		int cx0 = (Mth.floor(px) - blocksHalf - 16) >> 4;
		int cx1 = (Mth.floor(px) + blocksHalf + 16) >> 4;
		int cz0 = (Mth.floor(pz) - blocksHalf - 16) >> 4;
		int cz1 = (Mth.floor(pz) + blocksHalf + 16) >> 4;

		float centerX = x + size / 2f;
		float centerY = y + size / 2f;

		List<float[]> quads = new ArrayList<>();
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
						addQuad(quads, sx, sy, sx + pxPerBlock, sy + pxPerBlock, argb);
					}
				}
			}
		}

		// нерозвідана територія (ще не захоплена в кеш) лишається просто фоном панелі —
		// нічого не малюємо поверх, тому там видно темний фон панелі.
		drawQuads(graphics, quads);

		// мітки гравця, видимі в межах поточного вікна мінімапи
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

	private static void addQuad(List<float[]> quads, float x1, float y1, float x2, float y2, int argb) {
		float a = ((argb >>> 24) & 0xFF) / 255f;
		float r = ((argb >>> 16) & 0xFF) / 255f;
		float g = ((argb >>> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		quads.add(new float[] {x1, y1, x2, y2, r, g, b, a});
	}

	/**
	 * Малює всі квадратики рельєфу ОДНИМ draw call'ом (батчинг через
	 * Tesselator, той самий прийом, що вже використовується в
	 * TerritoryBorder/ChunkGridOverlay), а не окремим graphics.fill() на
	 * кожен блок — інакше мінімапа з високою деталізацією могла б суттєво
	 * просісти по продуктивності (сотні-тисячі окремих draw call'ів на кадр).
	 */
	private static void drawQuads(GuiGraphics graphics, List<float[]> quads) {
		if (quads.isEmpty()) return;

		Matrix4f pose = graphics.pose().last().pose();
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

		for (float[] q : quads) {
			buffer.addVertex(pose, q[0], q[1], 0).setColor(q[4], q[5], q[6], q[7]);
			buffer.addVertex(pose, q[2], q[1], 0).setColor(q[4], q[5], q[6], q[7]);
			buffer.addVertex(pose, q[2], q[3], 0).setColor(q[4], q[5], q[6], q[7]);
			buffer.addVertex(pose, q[0], q[3], 0).setColor(q[4], q[5], q[6], q[7]);
		}

		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}
}

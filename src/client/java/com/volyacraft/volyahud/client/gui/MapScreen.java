package com.volyacraft.volyahud.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Повноекранна карта: показує ВСІ чанки, кольори яких уже збережені в
 * {@link ChunkColorCache} для поточного виміру (тобто всі чанки, якими
 * гравець коли-небудь проходив, а не лише ті, що зараз завантажені),
 * дозволяє панорамувати/зумити й ставити іменовані мітки.
 *
 * Так само, як і мінімапа, малює ЛИШЕ рельєф — жодних мобів чи гравців.
 */
public class MapScreen extends Screen {

	private static final int[] WAYPOINT_COLORS = {
			0xFFFFFF, 0xFF5555, 0x55FF55, 0x5599FF, 0xFFD700, 0xFF55FF, 0x55FFFF
	};

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;
	private final String dimensionKey;

	/** Центр перегляду у світових координатах (X/Z). Стартує на позиції гравця. */
	private double viewX;
	private double viewZ;
	private int pxPerBlock = 3;

	private boolean dragging = false;
	private double dragStartMouseX, dragStartMouseY;
	private double dragStartViewX, dragStartViewZ;

	private int sidebarWidth = 150;
	private final List<WaypointRow> rows = new ArrayList<>();

	public MapScreen(Screen parent) {
		super(Component.literal("VolyaHUD — карта"));
		this.parent = parent;
		Minecraft mc = Minecraft.getInstance();
		this.dimensionKey = mc.level != null ? mc.level.dimension().location().toString() : "minecraft:overworld";
		if (mc.player != null) {
			this.viewX = mc.player.getX();
			this.viewZ = mc.player.getZ();
		}
	}

	private record WaypointRow(HudConfig.Waypoint waypoint, EditBox nameBox) {
	}

	@Override
	protected void init() {
		rebuildSidebar();

		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
				.bounds(this.width - sidebarWidth - 8, this.height - 26, sidebarWidth, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("До мене"), b -> centerOnPlayer())
				.bounds(8, this.height - 26, 100, 20).build());
	}

	private void rebuildSidebar() {
		rows.clear();
		List<HudConfig.Waypoint> list = cfg.waypointsFor(dimensionKey);
		int sideX = this.width - sidebarWidth;
		int y = 34;
		int max = Math.min(list.size(), (this.height - 70 - y) / 24);
		for (int i = 0; i < max; i++) {
			HudConfig.Waypoint wp = list.get(i);
			EditBox box = new EditBox(this.font, sideX, y, sidebarWidth - 22, 18, Component.literal("назва мітки"));
			box.setValue(wp.name);
			box.setMaxLength(32);
			box.setResponder(newName -> {
				wp.name = newName;
				cfg.save();
			});
			this.addRenderableWidget(box);

			Button delete = Button.builder(Component.literal("\u2715"), b -> {
				list.remove(wp);
				cfg.save();
				this.clearWidgets();
				this.init();
			}).bounds(sideX + sidebarWidth - 20, y, 20, 18).build();
			this.addRenderableWidget(delete);

			rows.add(new WaypointRow(wp, box));
			y += 24;
		}
	}

	private void centerOnPlayer() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			viewX = mc.player.getX();
			viewZ = mc.player.getZ();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int mapAreaW = this.width - sidebarWidth - 16;

		graphics.fill(0, 0, mapAreaW, this.height, 0xFF0B0B0B);
		graphics.enableScissor(0, 0, mapAreaW, this.height);
		drawTerrain(graphics, mapAreaW);
		drawWaypointsOnMap(graphics, mapAreaW);
		drawPlayerMarker(graphics);
		graphics.disableScissor();

		graphics.fill(mapAreaW, 0, mapAreaW + 2, this.height, 0x60FFFFFF);
		graphics.fill(mapAreaW + 2, 0, this.width, this.height, 0xC0000000);

		graphics.drawCenteredString(this.font, this.title, mapAreaW / 2, 8, 0xFFFFFF);
		graphics.drawString(this.font, "ЛКМ — поставити мітку, затиснути й тягнути — панорама, колесо — зум",
				8, this.height - 40, 0xA0A0A0, false);

		graphics.drawString(this.font, "Мітки:", this.width - sidebarWidth, 20, 0xFFFFFF, false);
		if (cfg.waypointsFor(dimensionKey).isEmpty()) {
			graphics.drawString(this.font, "(поки що немає)", this.width - sidebarWidth, 34, 0x808080, false);
		}

		super.render(graphics, mouseX, mouseY, partialTick);
	}

	// ОПТИМІЗАЦІЯ (та сама проблема, що й у MinimapHud): раніше весь рельєф
	// перебудовувався заново на КОЖНОМУ кадрі, поки карта відкрита — навіть
	// якщо гравець просто нерухомо дивиться на неї (не тягне мишею й не крутить
	// колесо). На великих зумах це перебір десятків тисяч блоків щосекунди
	// заради картинки, яка й так не змінюється. Тепер буфер квадів (примітивний
	// float[], без боксингу в List<float[]>) перебудовується лише тоді, коли
	// реально змінився вигляд карти (панорама/зум/розмір вікна/вимір), а не на
	// кожен виклик render().
	private float[] quadBuf = new float[64 * 8];
	private int quadFloats = 0;
	private double cachedViewX = Double.NaN, cachedViewZ = Double.NaN;
	private int cachedPxPerBlock = -1, cachedMapAreaW = -1, cachedHeight = -1;
	private long lastRebuildAtMs = 0L;
	/** Навіть якщо вигляд не змінився, раз на ~500мс усе одно освіжаємо буфер —
	 * щоб щойно довантажені сервером чанки з'являлись на відкритій карті, поки
	 * гравець нерухомо на неї дивиться, а не лише після наступного панорамування/зуму. */
	private static final long PERIODIC_REFRESH_MS = 500;

	private void drawTerrain(GuiGraphics graphics, int mapAreaW) {
		long now = System.currentTimeMillis();
		boolean viewChanged = viewX != cachedViewX || viewZ != cachedViewZ
				|| pxPerBlock != cachedPxPerBlock || mapAreaW != cachedMapAreaW || this.height != cachedHeight;
		boolean staleTimeout = now - lastRebuildAtMs >= PERIODIC_REFRESH_MS;
		if (viewChanged || staleTimeout) {
			rebuildTerrainBuffer(mapAreaW);
			cachedViewX = viewX; cachedViewZ = viewZ;
			cachedPxPerBlock = pxPerBlock; cachedMapAreaW = mapAreaW; cachedHeight = this.height;
			lastRebuildAtMs = now;
		}
		drawQuadBufferBatched(graphics);
	}

	private void rebuildTerrainBuffer(int mapAreaW) {
		quadFloats = 0;
		float centerX = mapAreaW / 2f;
		float centerY = this.height / 2f;

		double blocksHalfX = mapAreaW / 2.0 / pxPerBlock + 16;
		double blocksHalfY = this.height / 2.0 / pxPerBlock + 16;

		int cx0 = (int) Math.floor((viewX - blocksHalfX)) >> 4;
		int cx1 = (int) Math.floor((viewX + blocksHalfX)) >> 4;
		int cz0 = (int) Math.floor((viewZ - blocksHalfY)) >> 4;
		int cz1 = (int) Math.floor((viewZ + blocksHalfY)) >> 4;

		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				int[] colors = ChunkColorCache.getOrNull(dimensionKey, cx, cz);
				if (colors == null) continue;

				for (int lx = 0; lx < 16; lx++) {
					double wx = cx * 16 + lx;
					for (int lz = 0; lz < 16; lz++) {
						double wz = cz * 16 + lz;
						int argb = colors[lz * 16 + lx];
						float sx = centerX + (float) ((wx - viewX) * pxPerBlock);
						float sy = centerY + (float) ((wz - viewZ) * pxPerBlock);
						if (sx < -pxPerBlock || sy < -pxPerBlock || sx > mapAreaW + pxPerBlock || sy > this.height + pxPerBlock) continue;
						pushQuad(sx, sy, sx + pxPerBlock, sy + pxPerBlock, argb);
					}
				}
			}
		}
	}

	private void ensureQuadCapacity(int neededFloats) {
		if (quadBuf.length < neededFloats) {
			int newLen = quadBuf.length;
			while (newLen < neededFloats) newLen *= 2;
			quadBuf = java.util.Arrays.copyOf(quadBuf, newLen);
		}
	}

	private void pushQuad(float x1, float y1, float x2, float y2, int argb) {
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

	private void drawQuadBufferBatched(GuiGraphics graphics) {
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

		RenderSystem.setShader(net.minecraft.client.renderer.CoreShaders.POSITION_COLOR);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	private void drawWaypointsOnMap(GuiGraphics graphics, int mapAreaW) {
		float centerX = mapAreaW / 2f;
		float centerY = this.height / 2f;
		for (HudConfig.Waypoint wp : cfg.waypointsFor(dimensionKey)) {
			float sx = centerX + (float) ((wp.x - viewX) * pxPerBlock);
			float sy = centerY + (float) ((wp.z - viewZ) * pxPerBlock);
			if (sx < -20 || sy < -20 || sx > mapAreaW + 20 || sy > this.height + 20) continue;
			int color = 0xFF000000 | wp.color;
			graphics.fill((int) sx - 2, (int) sy - 6, (int) sx + 2, (int) sy + 6, color);
			graphics.fill((int) sx - 6, (int) sy - 2, (int) sx + 6, (int) sy + 2, color);
			graphics.drawCenteredString(this.font, wp.name, (int) sx, (int) sy + 8, 0xFFFFFF);
		}
	}

	private void drawPlayerMarker(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !dimensionKey.equals(mc.level.dimension().location().toString())) return;
		int mapAreaW = this.width - sidebarWidth - 16;
		float centerX = mapAreaW / 2f;
		float centerY = this.height / 2f;
		float sx = centerX + (float) ((mc.player.getX() - viewX) * pxPerBlock);
		float sy = centerY + (float) ((mc.player.getZ() - viewZ) * pxPerBlock);
		if (sx < 0 || sy < 0 || sx > mapAreaW || sy > this.height) return;
		graphics.fill((int) sx - 3, (int) sy - 3, (int) sx + 3, (int) sy + 3, 0xFFFFFFFF);
		graphics.fill((int) sx - 2, (int) sy - 2, (int) sx + 2, (int) sy + 2, 0xFFFF5555);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Спершу даємо шанс звичайним віджетам (кнопки "Готово"/"До мене" теж лежать
		// у зоні карти по X, тому клік по них не повинен ставити мітку під ними).
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		int mapAreaW = this.width - sidebarWidth - 16;
		if (mouseX <= mapAreaW) {
			if (button == 0) {
				double worldX = viewX + (mouseX - mapAreaW / 2.0) / pxPerBlock;
				double worldZ = viewZ + (mouseY - this.height / 2.0) / pxPerBlock;
				addWaypoint(worldX, worldZ);
				return true;
			}
			if (button == 1) {
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartViewX = viewX;
				dragStartViewZ = viewZ;
				return true;
			}
		}
		return false;
	}

	private void addWaypoint(double worldX, double worldZ) {
		List<HudConfig.Waypoint> list = cfg.waypointsFor(dimensionKey);
		int color = WAYPOINT_COLORS[list.size() % WAYPOINT_COLORS.length];
		Minecraft mc = Minecraft.getInstance();
		int y = mc.player != null ? Mth.floor(mc.player.getY()) : 64;
		list.add(new HudConfig.Waypoint("Мітка " + (list.size() + 1), Mth.floor(worldX), y, Mth.floor(worldZ), color));
		cfg.save();
		this.clearWidgets();
		this.init();
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (dragging && button == 1) {
			viewX = dragStartViewX - (mouseX - dragStartMouseX) / pxPerBlock;
			viewZ = dragStartViewZ - (mouseY - dragStartMouseY) / pxPerBlock;
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 1) {
			dragging = false;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY > 0) {
			pxPerBlock = Mth.clamp(pxPerBlock * 2, 1, 16);
		} else if (scrollY < 0) {
			pxPerBlock = Mth.clamp(pxPerBlock / 2, 1, 16);
		}
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		cfg.save();
		Minecraft.getInstance().setScreen(parent);
	}
}

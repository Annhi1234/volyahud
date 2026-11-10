package com.volyacraft.volyahud.client.gui;

import com.volyacraft.volyahud.client.VolyaHudClient;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import com.volyacraft.volyahud.client.hud.HudStyle;
import com.volyacraft.volyahud.client.hud.minimap.MapDecorations;
import com.volyacraft.volyahud.client.hud.minimap.MapTerrain;
import com.volyacraft.volyahud.client.hud.minimap.MapTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fullscreen map: shows the terrain of the current world exactly the way a
 * vanilla map does — a real texture with the vanilla lighting/shading
 * algorithm, drawn with a single blit — and lets you pan, zoom and place
 * named waypoints.
 *
 * <p>Like the minimap it draws ONLY terrain — no mobs and no players.
 */
public class MapScreen extends Screen {

	private static final int[] WAYPOINT_COLORS = {
			0xFFFFFF, 0xFF5555, 0x55FF55, 0x5599FF, 0xFFD700, 0xFF55FF, 0x55FFFF
	};

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;
	private final String dimensionKey;
	/** Ключ поточного світу/сервера — нові мітки прив'язуються до нього. */
	private final String worldKey;

	/** Centre of the view in world coordinates (X/Z). Starts at the player. */
	private double viewX;
	private double viewZ;
	private int pxPerBlock = 3;

	private boolean dragging = false;
	private double dragStartMouseX, dragStartMouseY;
	private double dragStartViewX, dragStartViewZ;

	private int sidebarWidth = 150;
	private final List<WaypointRow> rows = new ArrayList<>();
	/** Мітки, видимі в поточному світі (для шапки, порожнього стану й прокрутки). */
	private final List<HudConfig.Waypoint> visibleWaypoints = new ArrayList<>();
	/** Перша видима мітка в бічній колонці (коли міток більше, ніж влізає). */
	private int sidebarScroll = 0;
	/** Скільки рядків міток реально вміщається у поточну висоту вікна. */
	private int sidebarCapacity = 0;

	/** The terrain texture (built lazily and reused between frames). */
	private final MapTexture mapTexture;

	public MapScreen(Screen parent) {
		super(Component.literal("VolyaHUD — карта"));
		this.parent = parent;
		Minecraft mc = Minecraft.getInstance();
		this.dimensionKey = mc.level != null ? mc.level.dimension().location().toString() : "minecraft:overworld";
		this.worldKey = VolyaHudClient.currentWorldKey(mc);
		if (mc.player != null) {
			this.viewX = mc.player.getX();
			this.viewZ = mc.player.getZ();
		}
		this.mapTexture = new MapTexture(mc);
	}

	private record WaypointRow(HudConfig.Waypoint waypoint, EditBox nameBox, int y) {
	}

	@Override
	protected void init() {
		// Same reason as ConfigScreen: a window resize must rebuild the
		// sidebar widgets instead of stacking another copy on top of them.
		this.clearWidgets();
		this.sidebarWidth = Mth.clamp(this.width / 4, 136, 180);
		rebuildSidebar();

		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
				.bounds(this.width - sidebarWidth + 10, this.height - 26, sidebarWidth - 20, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("До мене"), b -> centerOnPlayer())
				.bounds(8, this.height - 26, 100, 20).build());
	}

	@Override
	public void onClose() {
		cfg.save();
		Minecraft.getInstance().setScreen(parent);
	}

	/**
	 * Звільнення GPU-текстури.
	 *
	 * <p>Викликається щоразу, коли екран закривається — незалежно від
	 * того, чи це кнопка "Готово", Escape, чи вихід зі світу. Тому текстура
	 * не "залишається" в пам'яті, якщо гру раптом закрили.
	 */
	@Override
	public void removed() {
		mapTexture.release();
		super.removed();
	}

	private void rebuildSidebar() {
		rows.clear();
		visibleWaypoints.clear();
		for (HudConfig.Waypoint wp : cfg.waypointsFor(dimensionKey)) {
			if (cfg.isWaypointVisible(wp, worldKey)) {
				visibleWaypoints.add(wp);
			}
		}
		int sideX = this.width - sidebarWidth;
		int contentX = sideX + 10;
		int gap = 4;
		int deleteW = 20;
		int centerW = 20;
		int boxW = Math.max(40, sidebarWidth - 20 - gap * 2 - deleteW - centerW);
		int y = 38;
		int capacity = Math.max(0, (this.height - 34 - y) / 24);
		this.sidebarCapacity = capacity;
		this.sidebarScroll = capacity <= 0 ? 0
				: Mth.clamp(this.sidebarScroll, 0, Math.max(0, visibleWaypoints.size() - capacity));
		int end = Math.min(visibleWaypoints.size(), this.sidebarScroll + capacity);
		Minecraft mc = Minecraft.getInstance();
		for (int i = this.sidebarScroll; i < end; i++) {
			HudConfig.Waypoint wp = visibleWaypoints.get(i);
			EditBox box = new EditBox(this.font, contentX, y, boxW, 18,
					Component.literal("назва мітки"));
			box.setValue(wp.name);
			box.setMaxLength(32);
			// LAG FIX: cfg.save() used to be called on EVERY keystroke while
			// typing a waypoint name (EditBox#setResponder fires per
			// character) — renaming a waypoint to 10 characters meant 10
			// separate synchronous JSON writes to disk in a row, which felt
			// like a small keyboard hang. The wp object itself changes in
			// memory right away (the marker shows the new name instantly),
			// and one write to disk is plenty — which already happens in
			// onClose() when leaving the map.
			box.setResponder(newName -> wp.name = newName);
			this.addRenderableWidget(box);

			String tip = "Перейти до мітки — X: " + wp.x + "  Y: " + wp.y + "  Z: " + wp.z;
			if (mc.player != null) {
				double dx = wp.x + 0.5 - mc.player.getX();
				double dz = wp.z + 0.5 - mc.player.getZ();
				tip += "  (відстань: " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " м)";
			}
			Button center = Button.builder(Component.literal("↗"), b -> {
				viewX = wp.x + 0.5;
				viewZ = wp.z + 0.5;
			}).bounds(contentX + boxW + gap, y, centerW, 18)
					.tooltip(Tooltip.create(Component.literal(tip))).build();
			this.addRenderableWidget(center);

			Button delete = Button.builder(Component.literal("✕"), b -> {
				cfg.removeWaypoint(dimensionKey, wp);
				cfg.save();
				this.clearWidgets();
				this.init();
			}).bounds(contentX + boxW + gap + centerW + gap, y, deleteW, 18).build();
			this.addRenderableWidget(delete);

			rows.add(new WaypointRow(wp, box, y));
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

	/**
	 * Мінімальний зум, за якого текстура (512×512 блоків) усе ще покриває
	 * весь видимий прямокутник карти. Без цього на великому вікні при
	 * GUI Scale 1 можна було зумити до 1 px/блок і побачити 1920 блоків —
	 * а текстура містить лише 512, тож краї ставали порожніми (темно-сірими)
	 * і виглядали як «карта зламалась». Тепер зум не опускається нижче
	 * межі, за якої картинка гарантовано заповнює екран.
	 */
	private int minPxPerBlock() {
		int mapAreaW = mapAreaW();
		int needed = Math.max(mapAreaW, Math.max(1, this.height));
		// Текстура 512×512 вирівняна по сітці 64 блоки, тому центр видимої
		// області лежить не рівно в її центрі, а зі зсувом 256..319 блоків
		// від лівого/верхнього краю. У найгіршому разі до протилежного краю
		// лишається 512 - 319 = 193 блоки. Ще 2 блоки зверху/справа/знизу
		// додає сам drawTerrain(), а центр може мати дробову частину — тому
		// для видимої частини беремо 190.
		int guaranteedMargin = MapTerrain.SIZE / 2 - MapTerrain.SIZE / 8 + 1;
		int visibleMargin = guaranteedMargin - 3;
		return Math.max(1, (int) Math.ceil(needed / (2.0 * visibleMargin)));
	}

	private int mapAreaW() {
		return Math.max(1, this.width - sidebarWidth - 6);
	}

	/**
	 * Карта сама малює свій фон. Стандартний фон екрана тут не потрібен:
	 * {@code super.render()} викликається останнім (щоб віджети були поверх
	 * карти), і без цього override він затемнював би вже намальований рельєф.
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Вікно могли розтягнути після останнього init() — тоді мінімальний
		// зум міг зрости. Піднімаємо поточний, щоб не було порожніх країв.
		pxPerBlock = Math.max(pxPerBlock, minPxPerBlock());

		int mapAreaW = mapAreaW();
		int sidebarX = this.width - sidebarWidth;

		// Загальний фон: темно-сірий градієнт замість майже чорної заливки.
		graphics.fillGradient(0, 0, this.width, this.height, 0xFF30333A, 0xFF1F2126);

		// Фон недослідженої частини карти — темно-сірий, як панелі HUD.
		graphics.fill(0, 0, mapAreaW, this.height, 0xFF292C32);
		graphics.enableScissor(0, 0, mapAreaW, this.height);
		drawTerrain(graphics, mapAreaW);
		if (cfg.mapTerritoryOverlay) {
			drawTerritory(graphics, mapAreaW);
		}
		if (cfg.mapChunkGrid) {
			drawChunkGrid(graphics, mapAreaW);
		}
		// Мітки малюються ПОВЕРХ рельєфу, справжніми ванільними спрайтами
		// маркерів (як у грі).
		// Лівий верхній кут видимої частини карти у світових координатах.
		double worldLeft = viewX - mapAreaW / 2.0 / pxPerBlock;
		double worldTop = viewZ - this.height / 2.0 / pxPerBlock;
		MapDecorations.render(graphics, Minecraft.getInstance(), dimensionKey, worldKey,
				worldLeft, worldTop, pxPerBlock, 0, 0, mapAreaW, this.height);
		drawPlayerMarker(graphics);
		graphics.disableScissor();

		// Тонкий розділювач + темна панель бічної колонки.
		graphics.fill(mapAreaW, 0, mapAreaW + 2, this.height, 0x60FFFFFF);
		HudStyle.panel(graphics, sidebarX, 0, this.width, this.height);

		// Заголовок на невеликій темній підкладці, щоб він читався на будь-якому
		// рельєфі (раніше білий текст міг зливатися зі снігом/піском).
		int titleW = this.font.width(this.title);
		int titleCx = mapAreaW / 2;
		graphics.fill(titleCx - titleW / 2 - 8, 4, titleCx + titleW / 2 + 8, 20, 0xB026292F);
		graphics.drawCenteredString(this.font, this.title, titleCx, 8, 0xFFF4F6F8);

		// Компактна підказка внизу карти — на власній підкладці, а не просто
		// сірим текстом поверх рельєфу.
		String help = mapAreaW >= 520
				? "ЛКМ — мітка   •   ПКМ + тягнути — панорама   •   Колесо — зум"
				: "ЛКМ — мітка   •   ПКМ — панорама   •   Колесо — зум";
		help = this.font.plainSubstrByWidth(help, Math.max(20, mapAreaW - 24));
		int helpW = this.font.width(help);
		graphics.fill(6, this.height - 43, Math.min(mapAreaW - 6, helpW + 14),
				this.height - 29, 0xB026292F);
		graphics.drawString(this.font, help, 9, this.height - 38, 0xC8CDD4, false);

		// Координати під курсором і поточний масштаб — маленький, але
		// зручний статус-рядок над підказкою.
		String status;
		if (mouseX >= 0 && mouseX <= mapAreaW && mouseY >= 0 && mouseY <= this.height) {
			int cursorX = Mth.floor(viewX + (mouseX - mapAreaW / 2.0) / pxPerBlock);
			int cursorZ = Mth.floor(viewZ + (mouseY - this.height / 2.0) / pxPerBlock);
			status = "X " + cursorX + "   Z " + cursorZ;
		} else {
			status = "Центр X " + Mth.floor(viewX) + "   Z " + Mth.floor(viewZ);
		}
		status += "   •   " + pxPerBlock + " px/блок";
		status = this.font.plainSubstrByWidth(status, Math.max(20, mapAreaW - 20));
		int statusW = this.font.width(status);
		int statusX = Math.max(6, mapAreaW - statusW - 14);
		graphics.fill(statusX - 4, this.height - 60, statusX + statusW + 4,
				this.height - 46, 0xB026292F);
		graphics.drawString(this.font, status, statusX, this.height - 55, 0xC8CDD4, false);

		// Заголовок бічної колонки з кількістю міток.
		List<HudConfig.Waypoint> waypoints = visibleWaypoints;
		HudStyle.header(graphics, this.font, "МІТКИ (" + waypoints.size() + ")",
				sidebarX + 10, 16, sidebarWidth - 20, HudStyle.ACCENT_CYAN);
		if (rows.size() < waypoints.size()) {
			// Скільки міток не влізло у видиму частину списку — маленький
			// бейдж у шапці, а не текст над кнопкою "Готово" (там він міг
			// накладатися на останній рядок).
			String more = "+" + (waypoints.size() - rows.size());
			int moreW = this.font.width(more);
			int moreX = sidebarX + sidebarWidth - 10 - moreW;
			graphics.fill(moreX - 3, 14, moreX + moreW + 3, 25, 0xB026292F);
			graphics.drawString(this.font, more, moreX, 16, HudStyle.TEXT_MUTED, false);
		}

		if (waypoints.isEmpty()) {
			graphics.drawString(this.font, "Поки що немає міток.", sidebarX + 10, 42,
					HudStyle.TEXT_MUTED, false);
			graphics.drawString(this.font, "Клікни лівою кнопкою по карті,", sidebarX + 10, 56,
					0xFF858C96, false);
			graphics.drawString(this.font, "щоб поставити першу.", sidebarX + 10, 68,
					0xFF858C96, false);
		}

		super.render(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * Малює рельєф ОДНИМ викликом blit з текстури, зібраної ванільним
	 * алгоритмом (світлотінь + палітра MapColor) — так само, як малюється
	 * ванільний предмет-мапа.
	 *
	 * <p>Раніше рельєф малювався тисячами окремих квадів через Tesselator —
	 * це десятки тисяч вершин на кадр і відчутні лаги. Тепер картинка
	 * збирається один раз у текстуру, а на екрані це один blit.
	 */
	private void drawTerrain(GuiGraphics graphics, int mapAreaW) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		// БАГ-ФІКС: раніше текстура завжди будувалася навколо ГРАВЦЯ, тому
		// при панорамі далі ~256 блоків від себе карта ставала порожньою —
		// у текстурі просто не було цих координат. Тепер текстура будується
		// навколо ЦЕНТРУ ПРОГЛЯДУ: панорама працює на будь-яку відстань, а
		// плавність зберігається, бо область вирівняна по сітці й
		// перебудовується лише при перетині її межі (раз на 64 блоки).
		int centerX = Mth.floor(viewX);
		int centerZ = Mth.floor(viewZ);
		mapTexture.update(dimensionKey, centerX, centerZ);

		int regionX = mapTexture.regionX();
		int regionZ = mapTexture.regionZ();

		int screenW = mapAreaW;
		int screenH = this.height;

		// Лівий верхній кут видимої області у світових координатах.
		double viewLeft = viewX - screenW / 2.0 / pxPerBlock;
		double viewTop = viewZ - screenH / 2.0 / pxPerBlock;

		// Ділянка текстури, що потрапляє у видиму область (у блоках).
		int srcX = (int) Math.floor(viewLeft) - regionX;
		int srcZ = (int) Math.floor(viewTop) - regionZ;
		int srcW = (int) Math.ceil(screenW / (double) pxPerBlock) + 2;
		int srcH = (int) Math.ceil(screenH / (double) pxPerBlock) + 2;

		// Частковий блок на краю — зсув на пів-блоку, щоб картинка не
		// "стрибала" при русі.
		double fracX = viewLeft - Math.floor(viewLeft);
		double fracZ = viewTop - Math.floor(viewTop);
		int destX = (int) Math.round(-fracX * pxPerBlock);
		int destY = (int) Math.round(-fracZ * pxPerBlock);
		int destW = srcW * pxPerBlock;
		int destH = srcH * pxPerBlock;

		mapTexture.drawRegion(graphics, destX, destY, destW, destH, srcX, srcZ, srcW, srcH);
	}

	/** Жовтий overlay позначених чанків території на повноекранній карті. */
	private void drawTerritory(GuiGraphics graphics, int mapAreaW) {
		Set<Long> claimed = TerritoryManager.claimedIn(worldKey, dimensionKey);
		if (claimed == null || claimed.isEmpty()) return;

		double worldLeft = viewX - mapAreaW / 2.0 / pxPerBlock;
		double worldTop = viewZ - this.height / 2.0 / pxPerBlock;
		for (long key : claimed) {
			int cx = ChunkPos.getX(key);
			int cz = ChunkPos.getZ(key);
			int sx = (int) Math.round((cx * 16 - worldLeft) * pxPerBlock);
			int sz = (int) Math.round((cz * 16 - worldTop) * pxPerBlock);
			int ex = (int) Math.round(((cx + 1) * 16 - worldLeft) * pxPerBlock);
			int ez = (int) Math.round(((cz + 1) * 16 - worldTop) * pxPerBlock);
			if (ex <= 0 || sx >= mapAreaW || ez <= 0 || sz >= this.height) continue;

			int x1 = Math.max(0, sx);
			int z1 = Math.max(0, sz);
			int x2 = Math.min(mapAreaW, ex);
			int z2 = Math.min(this.height, ez);
			if (x2 <= x1 || z2 <= z1) continue;

			graphics.fill(x1, z1, x2, z2, 0x28FFD700);
			graphics.fill(x1, z1, x2, z1 + 1, 0x90FFD700);
			graphics.fill(x1, z2 - 1, x2, z2, 0x90FFD700);
			graphics.fill(x1, z1, x1 + 1, z2, 0x90FFD700);
			graphics.fill(x2 - 1, z1, x2, z2, 0x90FFD700);
		}
	}

	/** Тонка сітка меж чанків на повноекранній карті. */
	private void drawChunkGrid(GuiGraphics graphics, int mapAreaW) {
		double worldLeft = viewX - mapAreaW / 2.0 / pxPerBlock;
		double worldTop = viewZ - this.height / 2.0 / pxPerBlock;
		int minChunkX = Mth.floor(worldLeft) >> 4;
		int maxChunkX = Mth.floor(worldLeft + mapAreaW / (double) pxPerBlock) >> 4;
		int minChunkZ = Mth.floor(worldTop) >> 4;
		int maxChunkZ = Mth.floor(worldTop + this.height / (double) pxPerBlock) >> 4;

		for (int cx = minChunkX; cx <= maxChunkX + 1; cx++) {
			int sx = (int) Math.round((cx * 16 - worldLeft) * pxPerBlock);
			if (sx >= 0 && sx < mapAreaW) {
				graphics.fill(sx, 0, sx + 1, this.height, 0x30FFFFFF);
			}
		}
		for (int cz = minChunkZ; cz <= maxChunkZ + 1; cz++) {
			int sz = (int) Math.round((cz * 16 - worldTop) * pxPerBlock);
			if (sz >= 0 && sz < this.height) {
				graphics.fill(0, sz, mapAreaW, sz + 1, 0x30FFFFFF);
			}
		}
	}

	private void drawPlayerMarker(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null
				|| !dimensionKey.equals(mc.level.dimension().location().toString())) return;
		int mapAreaW = mapAreaW();
		float centerX = mapAreaW / 2f;
		float centerY = this.height / 2f;
		float sx = centerX + (float) ((mc.player.getX() - viewX) * pxPerBlock);
		float sy = centerY + (float) ((mc.player.getZ() - viewZ) * pxPerBlock);
		if (sx < 0 || sy < 0 || sx > mapAreaW || sy > this.height) return;

		int cx = Math.round(sx);
		int cy = Math.round(sy);
		// Напрямок погляду (та сама формула, що й на мінімапі).
		double yawRad = Math.toRadians(mc.player.getYRot());
		double dirX = -Math.sin(yawRad);
		double dirZ = Math.cos(yawRad);

		// М'яка тінь, щоб маркер не губився на світлому рельєфі.
		graphics.fill(cx - 6, cy - 6, cx + 6, cy + 6, 0x70000000);
		// "Ніс" маркера — коротка лінія в бік погляду.
		for (int i = 2; i <= 8; i++) {
			int px = cx + (int) Math.round(dirX * i);
			int py = cy + (int) Math.round(dirZ * i);
			graphics.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
		}
		// Центр: білий контур + червона середина.
		graphics.fill(cx - 4, cy - 4, cx + 4, cy + 4, 0xFFFFFFFF);
		graphics.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFFFF5555);
		graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFDADA);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Спершу даємо шанс звичайним віджетам (кнопки "Готово"/"До мене" теж лежать
		// у зоні карти по X, тому клік по них не повинен ставити мітку під ними).
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		int mapAreaW = mapAreaW();
		// Не додаємо мітку, якщо клік припав на заголовок/підказку/рядок
		// координат або взагалі за межами вікна. Раніше mouseClicked не
		// перевіряв Y, тому мітка могла з'явитися під службовим overlay.
		boolean overMap = mouseX >= 0 && mouseX <= mapAreaW
				&& mouseY >= 24 && mouseY < this.height - 64;
		if (overMap) {
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
		int visibleCount = cfg.countVisibleWaypoints(dimensionKey, worldKey);
		int color = WAYPOINT_COLORS[visibleCount % WAYPOINT_COLORS.length];
		Minecraft mc = Minecraft.getInstance();
		int y = mc.player != null ? Mth.floor(mc.player.getY()) : 64;
		HudConfig.Waypoint wp = new HudConfig.Waypoint(cfg.nextWaypointName(dimensionKey, worldKey),
				Mth.floor(worldX), y, Mth.floor(worldZ), color);
		wp.world = worldKey;
		list.add(wp);
		cfg.save();
		// Нова мітка має бути видно одразу, навіть якщо список уже прокручений
		// або міток більше, ніж вміщається.
		this.sidebarScroll = Integer.MAX_VALUE;
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
		// Над бічною колонкою колесо гортає список міток, а не масштабує
		// карту: інакше при великій кількості міток до нижніх рядків просто
		// не було б як дістатися (вони не створюються поза видимою зоною).
		if (mouseX >= this.width - sidebarWidth) {
			int maxScroll = Math.max(0, visibleWaypoints.size() - sidebarCapacity);
			if (maxScroll > 0 && scrollY != 0) {
				int next = Mth.clamp(sidebarScroll + (scrollY > 0 ? -1 : 1), 0, maxScroll);
				if (next != sidebarScroll) {
					sidebarScroll = next;
					this.clearWidgets();
					this.init();
				}
			}
			return true;
		}

		int minZoom = minPxPerBlock();
		if (scrollY > 0) {
			pxPerBlock = Mth.clamp(pxPerBlock * 2, minZoom, 16);
		} else if (scrollY < 0) {
			pxPerBlock = Mth.clamp(pxPerBlock / 2, minZoom, 16);
		}
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

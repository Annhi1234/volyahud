package com.volyacraft.volyahud.client.hud.minimap;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Малює мітки на карті СПРАЖНІМИ ванільними спрайтами маркерів.
 *
 * <p>У Minecraft у кожної мапи-предмета є «декорації» — маленькі значки
 * поверх картинки: маркери, прапорці, позначки гравця тощо. Вони живуть у
 * спрайт-атласі {@code textures/atlas/map_decorations.png} і малюються
 * рівно так, як у ванільній мапі.
 *
 * <p>Раніше мітки малювалися просто хрестиками з намальованих прямокутників
 * ({@code graphics.fill}) — це виглядало як «сині плюсики» і зовсім не було
 * схоже на те, до чого звикли гравці в інших модах. Тепер це справжні
 * ванільні маркери — ті самі спрайти, що й у грі.
 */
public final class MapDecorations {

	private MapDecorations() {
	}

	/**
	 * Кеш спрайтів за id мітки — щоб не шукати їх у реєстрі на кожному
	 * кадрі. Ідентифікатор мітки — це її колір, тож для кольору знаходимо
	 * найближчий ванільний спрайт маркера.
	 */
	private static final java.util.Map<Integer, TextureAtlasSprite> SPRITE_CACHE = new java.util.HashMap<>();
	/** Керівник текстур, для якого побудований SPRITE_CACHE (скидається при reload ресурсів). */
	private static Object cachedDecorationManager;

	/**
	 * Маркери, які малювати цього кадру.
	 *
	 * <p>ОПТИМІЗАЦІЯ: раніше на кожен маркер створювався новий об'єкт
	 * (record) — тобто на кожен кадр по алокації на кожну мітку. Тепер
	 * структури перевикористовуються: масив об'єктів перезаписується, а
	 * лічильник каже, скільки з них актуальних.
	 */
	private static Marker[] MARKERS = new Marker[64];
	private static int markerCount = 0;

	private static final class Marker {
		TextureAtlasSprite sprite;
		int x;
		int y;
		int rot;
		boolean showName;
		String name;
	}

	/**
	 * Малює всі мітки поточного виміру у ванільному стилі.
	 *
	 * @param worldLeft     світовий X у лівому верхньому куті області малювання
	 * @param worldTop      світовий Z у лівому верхньому куті області малювання
	 * @param pixelsPerBlock скільки екранних пікселів займає один блок
	 * @param originX       екранний X лівого верхнього кута області (панелі)
	 * @param originY       екранний Y лівого верхнього кута області (панелі)
	 * @param clipW         ширина області малювання (для відсікання)
	 * @param clipH         висота області малювання (для відсікання)
	 *
	 * <p>БАГ-ФІКС: раніше {@code originX/originY} не передавалися взагалі, і
	 * координати міток рахувалися відносно панелі, але малювалися в
	 * абсолютних екранних координатах. Через це на мінімапі (яка стоїть у
	 * куті, а не в (0,0)) всі мітки з'являлися в лівому верхньому куті
	 * екрана, а не на своїх місцях.
	 */
	public static void render(GuiGraphics graphics, Minecraft mc, String dimensionKey, String worldKey,
			double worldLeft, double worldTop, double pixelsPerBlock,
			int originX, int originY, int clipW, int clipH) {
		List<HudConfig.Waypoint> waypoints = HudConfig.INSTANCE.waypointsFor(dimensionKey);
		if (waypoints.isEmpty()) return;

		markerCount = 0;

		for (HudConfig.Waypoint wp : waypoints) {
			if (!HudConfig.INSTANCE.isWaypointVisible(wp, worldKey)) continue;
			// Позиція відносно панелі...
			double relX = (wp.x - worldLeft) * pixelsPerBlock;
			double relY = (wp.z - worldTop) * pixelsPerBlock;
			// ...і лише потім — абсолютна екранна позиція.
			if (relX < -16 || relY < -16 || relX > clipW + 16 || relY > clipH + 16) continue;

			TextureAtlasSprite sprite = spriteFor(wp.color);
			if (sprite == null) continue;

			if (markerCount >= MARKERS.length) {
				// Більше міток, ніж очікували — розширюємо масив.
				Marker[] bigger = new Marker[MARKERS.length * 2];
				System.arraycopy(MARKERS, 0, bigger, 0, MARKERS.length);
				MARKERS = bigger;
			}

			Marker marker = MARKERS[markerCount];
			if (marker == null) {
				marker = new Marker();
				MARKERS[markerCount] = marker;
			}
			marker.sprite = sprite;
			marker.x = (int) Math.round(originX + relX);
			marker.y = (int) Math.round(originY + relY);
			marker.rot = 0;
			marker.showName = true;
			marker.name = wp.name;
			markerCount++;
		}

		for (int i = 0; i < markerCount; i++) {
			drawMarker(graphics, mc, MARKERS[i]);
		}
	}

	/**
	 * Малює один маркер — тим самим прийомом, що й ванільна гра: спрайт
	 * 4x4 блоки, повернутий за потреби, поверх картинки мапи.
	 */
	private static void drawMarker(GuiGraphics graphics, Minecraft mc, Marker marker) {
		graphics.pose().pushPose();
		// Позиція маркера — його центр, як у ваніли.
		graphics.pose().translate(marker.x, marker.y, 0.0f);
		if (marker.rot != 0) {
			graphics.pose().mulPose(net.minecraft.core.Direction.from2DDataValue(marker.rot)
					.getRotation());
		}

		// Маркер — спрайт 8x8 (як у ванільній мапі). Малюємо його в
		// натуральному розмірі, щоб він залишався чітким (без блуріння) і
		// виглядав точно так, як маркери в грі.
		graphics.blitSprite(
				net.minecraft.client.renderer.RenderType::guiTextured,
				marker.sprite,
				-4, -4, 8, 8,
				0xFFFFFFFF
		);
		graphics.pose().popPose();

		if (marker.showName && marker.name != null && !marker.name.isEmpty()) {
			graphics.drawCenteredString(mc.font, marker.name, marker.x, marker.y + 6, 0xFFFFFF);
		}
	}

	/**
	 * Знаходить ванільний спрайт маркера для кольору мітки.
	 *
	 * <p>У гри є фіксовані маркери: {@code red_marker} та {@code blue_marker}
	 * (звичайні й магічні). Ми беремо той, що відповідає кольору мітки —
	 * щоб усі мітки на карті виглядали як справжні мапи, а не як намальовані
	 * хрестики.
	 */
	private static TextureAtlasSprite spriteFor(int color) {
		Minecraft mc = Minecraft.getInstance();
		// Після F3+T / зміни пакета ресурсів менеджер декорацій створюється
		// заново, а старі спрайти вже недійсні. Інакше карта могла малювати
		// «биті» або зниклі маркери до перезаходу в гру.
		Object manager = mc.getMapDecorationTextures();
		if (manager != cachedDecorationManager) {
			SPRITE_CACHE.clear();
			cachedDecorationManager = manager;
		}

		TextureAtlasSprite cached = SPRITE_CACHE.get(color);
		if (cached != null) return cached;

		TextureAtlasSprite sprite = null;
		try {
			Holder<MapDecorationType> type = typeFor(color);
			if (type != null) {
				// MapDecoration#getSpriteLocation() повертає шлях до спрайта
				// в атласі декорацій; MapDecorationTextureManager вміє його
				// знайти.
				MapDecoration decoration = new MapDecoration(type, (byte) 0, (byte) 0, (byte) 0, Optional.empty());
				sprite = mc.getMapDecorationTextures().get(decoration);
			}
		} catch (Throwable ignored) {
			// Якщо ванільний спрайт недоступний — просто не малюємо маркер
			// (карта й далі працює, просто без значків).
			sprite = null;
		}

		if (sprite != null) {
			SPRITE_CACHE.put(color, sprite);
		}
		return sprite;
	}

	/**
	 * Обирає тип маркера за кольором мітки: червоні мітки — звичайний
	 * маркер, сині — «магічний» (як у ванільній мапі, де так позначають
	 * цілі, знайдені за допомогою ока).
	 */
	private static Holder<MapDecorationType> typeFor(int color) {
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;

		// Синя переважає над червоною, якщо вона помітно синіша.
		if (b > r + 30 && b > g + 30) {
			return MapDecorationTypes.BLUE_MARKER;
		}
		if (r > g + 30 && r > b + 30) {
			return MapDecorationTypes.RED_MARKER;
		}
		// Для решти кольорів беремо базовий червоний маркер.
		return MapDecorationTypes.RED_MARKER;
	}

	/** Скидає кеш спрайтів (при перезаході в гру — атлас міг перезавантажитися). */
	public static void reset() {
		SPRITE_CACHE.clear();
		cachedDecorationManager = null;
	}
}

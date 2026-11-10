package com.volyacraft.volyahud.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.VolyaHudClient;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Візуалізація кордону "своєї території" (як в Towny — де закінчується
 * твоє місто): напівпрозорі жовті "стіни" малюються рівно там, де закінчується
 * позначений тобою чанк і починається чужий/непозначений.
 *
 * ЧЕСНО про обмеження: клієнтський мод не має доступу до внутрішніх даних
 * серверного плагіна (Towny чи будь-якого іншого) — сервер їх просто не
 * надсилає клієнту по мережі. Тому межі тут НЕ підтягуються з Towny
 * автоматично: ти сам позначаєш свої чанки (клавіша J, або кнопка в меню
 * налаштувань), і саме ці позначені чанки підсвічуються. Якщо колись
 * захочеш повну автоматичну синхронізацію з реальними межами міста —
 * знадобиться вже серверний плагін/аддон, який шле ці межі клієнту
 * окремим пакетом.
 */
public class TerritoryBorder {

	/** Наскільки далеко (у чанках) від гравця перевіряємо кордони — для продуктивності. */
	private static final int RENDER_RADIUS_CHUNKS = 6;
	private static final int WALL_BELOW = 6;
	private static final int WALL_ABOVE = 24;
	private static final float R = 1f, G = 0.85f, B = 0.25f, A = 0.28f;

	/**
	 * ОПТИМІЗАЦІЯ: раніше кордон перераховувався ПОВНІСТЮ на кожному кадрі —
	 * сканувався квадрат 13x13 чанків (169 чанків) і для кожного робилося
	 * до 5 пошуків у множині, тобто ~850 хеш-пошуків НА КАДР. На 144 Гц
	 * це понад сотню тисяч пошуків на секунду без жодної користі: кордон
	 * не змінюється, поки гравець не перейшов межу чанку й не додав/прибрав
	 * позначку.
	 *
	 * <p>Тепер ребро кордону перераховується лише тоді, коли змінилося
	 * щось із цього: чанок гравця, вимір або сам набір позначених чанків.
	 * Список квадів перевикористовується (без алокацій на кадр).
	 */
	private static final List<double[]> QUADS = new ArrayList<>();
	private static int cachedChunkX = Integer.MIN_VALUE;
	private static int cachedChunkZ = Integer.MIN_VALUE;
	private static String cachedDimension = null;
	private static String cachedWorld = null;
	private static int cachedClaimCount = -1;
	private static int cachedClaimRevision = -1;
	private static float cachedMinY = Float.NaN;
	private static float cachedMaxY = Float.NaN;

	public static void render(WorldRenderContext context, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.territoryBorder) return;
		if (mc.player == null || mc.level == null) return;

		String dimKey = mc.level.dimension().location().toString();
		String worldKey = VolyaHudClient.currentWorldKey(mc);
		Set<Long> claimed = TerritoryManager.claimedIn(worldKey, dimKey);
		if (claimed == null || claimed.isEmpty()) {
			QUADS.clear();
			cachedChunkX = Integer.MIN_VALUE;
			return;
		}

		int pcx = Mth.floor(mc.player.getX()) >> 4;
		int pcz = Mth.floor(mc.player.getZ()) >> 4;
		int minY = Mth.floor(mc.player.getY()) - WALL_BELOW;
		int maxY = Mth.floor(mc.player.getY()) + WALL_ABOVE;

		boolean needRebuild = pcx != cachedChunkX || pcz != cachedChunkZ
				|| !dimKey.equals(cachedDimension)
				|| !worldKey.equals(cachedWorld)
				|| claimed.size() != cachedClaimCount
				|| TerritoryManager.revision() != cachedClaimRevision
				|| minY != cachedMinY || maxY != cachedMaxY;

		if (needRebuild) {
			QUADS.clear();

			for (int cx = pcx - RENDER_RADIUS_CHUNKS; cx <= pcx + RENDER_RADIUS_CHUNKS; cx++) {
				for (int cz = pcz - RENDER_RADIUS_CHUNKS; cz <= pcz + RENDER_RADIUS_CHUNKS; cz++) {
					if (!claimed.contains(ChunkPos.asLong(cx, cz))) continue;

					addEdgeIfBorder(claimed, QUADS, cx, cz, cx + 1, cz, minY, maxY);
					addEdgeIfBorder(claimed, QUADS, cx, cz, cx - 1, cz, minY, maxY);
					addEdgeIfBorder(claimed, QUADS, cx, cz, cx, cz + 1, minY, maxY);
					addEdgeIfBorder(claimed, QUADS, cx, cz, cx, cz - 1, minY, maxY);
				}
			}

			cachedChunkX = pcx;
			cachedChunkZ = pcz;
			cachedDimension = dimKey;
			cachedWorld = worldKey;
			cachedClaimCount = claimed.size();
			cachedClaimRevision = TerritoryManager.revision();
			cachedMinY = minY;
			cachedMaxY = maxY;
		}

		if (QUADS.isEmpty()) return;

		// БАГ-ФІКС (точність далеко від спавну): координати квадів тепер вже
		// камера-відносні (double, віднято на CPU в addEdgeIfBorder), тому
		// саму трансляцію камери в матрицю більше не пишемо — лишається лише
		// поворот, як і скрізь у ArmorHud/ChunkGridOverlay.
		Matrix4f pose = context.matrixStack().last().pose();

		drawQuads(pose, QUADS, R, G, B, A, context.camera().getPosition());
	}

	/**
	 * Додає стінку-квад на межі (cx,cz)|(ncx,ncz), тільки якщо сусідній
	 * чанк НЕ позначений (тобто це справжня межа території).
	 *
	 * <p>Координати зберігаються У СВІТОВИХ (double), а не камера-відносні:
	 * список квадів кешується, а камера рухається щокадру — тож віднімати
	 * позицію камери треба вже під час малювання, інакше стіни "відставали
	 * б" від світу. Віднімання робиться на CPU в double (ще до касту в
	 * float) — саме так уникнуто втрати точності далеко від спавну.
	 */
	private static void addEdgeIfBorder(Set<Long> claimed, List<double[]> quads, int cx, int cz, int ncx, int ncz, int minY, int maxY) {
		if (claimed.contains(ChunkPos.asLong(ncx, ncz))) return;

		double x1, z1, x2, z2;
		if (ncx != cx) {
			double wallX = Math.max(cx, ncx) * 16.0;
			x1 = wallX; z1 = cz * 16.0;
			x2 = wallX; z2 = (cz + 1) * 16.0;
		} else {
			double wallZ = Math.max(cz, ncz) * 16.0;
			x1 = cx * 16.0; z1 = wallZ;
			x2 = (cx + 1) * 16.0; z2 = wallZ;
		}

		quads.add(new double[] {
				x1, minY, z1,
				x2, minY, z2,
				x2, maxY, z2,
				x1, maxY, z1
		});
	}

	private static void drawQuads(Matrix4f pose, List<double[]> quads, float r, float g, float b, float a, Vec3 camPos) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

		// Позиція камери віднімається тут, на CPU, у double — і лише потім
		// кастується у float (див. коментар до addEdgeIfBorder).
		double camX = camPos.x;
		double camY = camPos.y;
		double camZ = camPos.z;

		for (double[] q : quads) {
			for (int i = 0; i < 4; i++) {
				buffer.addVertex(pose,
						(float) (q[i * 3] - camX),
						(float) (q[i * 3 + 1] - camY),
						(float) (q[i * 3 + 2] - camZ))
						.setColor(r, g, b, a);
			}
		}

		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull(); // стіну має бути видно з обох боків (зсередини й ззовні території)

		BufferUploader.drawWithShader(buffer.buildOrThrow());

		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}
}

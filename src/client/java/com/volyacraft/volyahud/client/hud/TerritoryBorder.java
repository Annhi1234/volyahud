package com.volyacraft.volyahud.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.config.HudConfig;
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

	public static void render(WorldRenderContext context, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.territoryBorder) return;
		if (mc.player == null || mc.level == null) return;

		String dimKey = mc.level.dimension().location().toString();
		Set<Long> claimed = cfg.territoryChunks.get(dimKey);
		if (claimed == null || claimed.isEmpty()) return;

		int pcx = Mth.floor(mc.player.getX()) >> 4;
		int pcz = Mth.floor(mc.player.getZ()) >> 4;
		int minY = Mth.floor(mc.player.getY()) - WALL_BELOW;
		int maxY = Mth.floor(mc.player.getY()) + WALL_ABOVE;

		Vec3 camPos = context.camera().getPosition();

		List<float[]> quads = new ArrayList<>();

		for (int cx = pcx - RENDER_RADIUS_CHUNKS; cx <= pcx + RENDER_RADIUS_CHUNKS; cx++) {
			for (int cz = pcz - RENDER_RADIUS_CHUNKS; cz <= pcz + RENDER_RADIUS_CHUNKS; cz++) {
				if (!claimed.contains(ChunkPos.asLong(cx, cz))) continue;

				addEdgeIfBorder(claimed, quads, cx, cz, cx + 1, cz, minY, maxY, camPos);
				addEdgeIfBorder(claimed, quads, cx, cz, cx - 1, cz, minY, maxY, camPos);
				addEdgeIfBorder(claimed, quads, cx, cz, cx, cz + 1, minY, maxY, camPos);
				addEdgeIfBorder(claimed, quads, cx, cz, cx, cz - 1, minY, maxY, camPos);
			}
		}

		if (quads.isEmpty()) return;

		// БАГ-ФІКС (точність далеко від спавну): координати квадів тепер вже
		// камера-відносні (double, віднято на CPU в addEdgeIfBorder), тому
		// саму трансляцію камери в матрицю більше не пишемо — лишається лише
		// поворот, як і скрізь у ArmorHud/ChunkGridOverlay.
		Matrix4f pose = context.matrixStack().last().pose();

		drawQuads(pose, quads, R, G, B, A);
	}

	/** Додає стінку-квад на межі (cx,cz)|(ncx,ncz), тільки якщо сусідній чанк НЕ позначений (тобто це справжня межа території). Координати одразу камера-відносні. */
	private static void addEdgeIfBorder(Set<Long> claimed, List<float[]> quads, int cx, int cz, int ncx, int ncz, int minY, int maxY, Vec3 camPos) {
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

		double relMinY = minY - camPos.y;
		double relMaxY = maxY - camPos.y;
		quads.add(new float[] {
				(float) (x1 - camPos.x), (float) relMinY, (float) (z1 - camPos.z),
				(float) (x2 - camPos.x), (float) relMinY, (float) (z2 - camPos.z),
				(float) (x2 - camPos.x), (float) relMaxY, (float) (z2 - camPos.z),
				(float) (x1 - camPos.x), (float) relMaxY, (float) (z1 - camPos.z)
		});
	}

	private static void drawQuads(Matrix4f pose, List<float[]> quads, float r, float g, float b, float a) {
		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

		for (float[] q : quads) {
			for (int i = 0; i < 4; i++) {
				buffer.addVertex(pose, q[i * 3], q[i * 3 + 1], q[i * 3 + 2]).setColor(r, g, b, a);
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

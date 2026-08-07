package com.volyacraft.volyahud.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Допоміжна сітка меж чанків для рівної забудови: тонкі вертикальні лінії
 * на кожній межі чанка (кожні 16 блоків) навколо гравця, плюс підпис
 * "16 x 16" прямо на стінах чанка, в якому гравець зараз стоїть — щоб
 * одразу було видно, скільки блоків влазить у чанк по кожній стороні.
 *
 * Суто клієнтський візуальний інструмент: нічого не змінює у світі,
 * нічого не читає із сервера, працює абсолютно так само на будь-якому
 * сервері/у творчому світі.
 *
 * Лінії малюються тим самим низькорівневим способом (Tesselator + один
 * з CoreShaders), що вже використовується в PearlTrajectory для сліду
 * перли — цей прийом у моді вже перевірений і працює.
 */
public class ChunkGridOverlay {

	/** Скільки чанків навколо гравця охоплює сітка ліній (у кожен бік). */
	private static final int RADIUS_CHUNKS = 3;
	/** Наскільки високо/низько від гравця тягнуться вертикальні лінії. */
	private static final int VERTICAL_RANGE = 24;
	private static final float LINE_WIDTH = 1.4f;

	private record Label(int x, int y, String text) {
	}

	private static final List<Label> LABELS = new ArrayList<>();

	/** Викликається у 3D-проході (WorldRenderEvents.AFTER_ENTITIES). */
	public static void renderWorld(WorldRenderContext context, Minecraft mc) {
		LABELS.clear();

		HudConfig cfg = HudConfig.INSTANCE;
		if (!cfg.chunkGrid) return;
		if (mc.player == null || mc.level == null) return;

		Vec3 camPos = context.camera().getPosition();
		PoseStack matrices = context.matrixStack();
		matrices.pushPose();
		matrices.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f pose = matrices.last().pose();

		int pcx = Mth.floor(mc.player.getX()) >> 4;
		int pcz = Mth.floor(mc.player.getZ()) >> 4;
		int minY = Mth.floor(mc.player.getY()) - VERTICAL_RANGE;
		int maxY = Mth.floor(mc.player.getY()) + VERTICAL_RANGE;
		// ПОКРАЩЕННЯ: рівень "підлоги" сітки — прямо під ногами гравця, щоб
		// лінії по землі було видно навіть здалеку (а не лише вертикальні
		// стовпчики на перетинах, як раніше — це радше виглядало як паркан
		// зі стовпів, ніж як сітка меж чанка).
		double floorY = Mth.floor(mc.player.getY());

		double spanMin = (pcx - RADIUS_CHUNKS) * 16.0;
		double spanMax = (pcx + RADIUS_CHUNKS + 1) * 16.0;
		double spanMinZ = (pcz - RADIUS_CHUNKS) * 16.0;
		double spanMaxZ = (pcz + RADIUS_CHUNKS + 1) * 16.0;

		List<Vec3> segments = new ArrayList<>();
		for (int cx = -RADIUS_CHUNKS; cx <= RADIUS_CHUNKS + 1; cx++) {
			double x = (pcx + cx) * 16.0;
			for (int cz = -RADIUS_CHUNKS; cz <= RADIUS_CHUNKS + 1; cz++) {
				double z = (pcz + cz) * 16.0;
				// вертикальний стовпчик на перетині меж (видно й зверху, і знизу)
				segments.add(new Vec3(x, minY, z));
				segments.add(new Vec3(x, maxY, z));
			}
			// повна лінія межі чанка по землі вздовж осі Z (справжня "стіна" сітки)
			segments.add(new Vec3(x, floorY, spanMinZ));
			segments.add(new Vec3(x, floorY, spanMaxZ));
		}
		for (int cz = -RADIUS_CHUNKS; cz <= RADIUS_CHUNKS + 1; cz++) {
			double z = (pcz + cz) * 16.0;
			// і вздовж осі X
			segments.add(new Vec3(spanMin, floorY, z));
			segments.add(new Vec3(spanMax, floorY, z));
		}

		drawLines(pose, segments, 0.4f, 0.85f, 1f, 0.5f);

		matrices.popPose();

		// Підписи "16 x 16" на 4 стінах ПОТОЧНОГО чанка. Малюємо їх пізніше,
		// у 2D HUD-проході — так само, як мітки гравців (ArmorHud/OverlayQueue):
		// тут лише проєктуємо світові точки в екранні координати.
		if (cfg.chunkGridLabels) {
			double midY = mc.player.getEyeY();
			double x0 = pcx * 16.0, x1 = (pcx + 1) * 16.0;
			double z0 = pcz * 16.0, z1 = (pcz + 1) * 16.0;
			double midX = (x0 + x1) / 2.0, midZ = (z0 + z1) / 2.0;

			queueLabel(context, mc, new Vec3(x0, midY, midZ), "16 \u00D7 16");
			queueLabel(context, mc, new Vec3(x1, midY, midZ), "16 \u00D7 16");
			queueLabel(context, mc, new Vec3(midX, midY, z0), "16 \u00D7 16");
			queueLabel(context, mc, new Vec3(midX, midY, z1), "16 \u00D7 16");
		}
	}

	private static void queueLabel(WorldRenderContext context, Minecraft mc, Vec3 worldPos, String text) {
		Matrix4f viewProj = new Matrix4f(context.projectionMatrix())
				.mul(context.matrixStack().last().pose());
		Vec3 camPos = context.camera().getPosition();

		double wx = worldPos.x - camPos.x;
		double wy = worldPos.y - camPos.y;
		double wz = worldPos.z - camPos.z;

		Vector4f clip = new Vector4f((float) wx, (float) wy, (float) wz, 1.0f);
		clip.mul(viewProj);
		if (clip.w <= 0.01f) return; // позаду камери

		int screenW = mc.getWindow().getGuiScaledWidth();
		int screenH = mc.getWindow().getGuiScaledHeight();
		int sx = (int) ((clip.x / clip.w * 0.5 + 0.5) * screenW);
		int sy = (int) ((1 - (clip.y / clip.w * 0.5 + 0.5)) * screenH);

		if (sx < -40 || sx > screenW + 40 || sy < -20 || sy > screenH + 20) return;
		LABELS.add(new Label(sx, sy, text));
	}

	/** Викликається у 2D-проході (HudRenderCallback), після renderWorld того ж кадру. */
	public static void renderHud(GuiGraphics graphics, Minecraft mc) {
		if (LABELS.isEmpty()) return;
		for (Label label : LABELS) {
			int w = mc.font.width(label.text());
			graphics.fill(label.x() - w / 2 - 3, label.y() - 6, label.x() + w / 2 + 3, label.y() + 6, 0x70000000);
			graphics.drawCenteredString(mc.font, label.text(), label.x(), label.y() - 4, 0x55D5FF);
		}
	}

	private static void drawLines(Matrix4f pose, List<Vec3> points, float r, float g, float b, float a) {
		if (points.size() < 2) return;

		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

		for (int i = 0; i + 1 < points.size(); i += 2) {
			Vec3 a1 = points.get(i);
			Vec3 a2 = points.get(i + 1);
			Vec3 normal = a2.subtract(a1).normalize();
			buffer.addVertex(pose, (float) a1.x, (float) a1.y, (float) a1.z)
					.setColor(r, g, b, a)
					.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
			buffer.addVertex(pose, (float) a2.x, (float) a2.y, (float) a2.z)
					.setColor(r, g, b, a)
					.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
		}

		RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.lineWidth(LINE_WIDTH);

		BufferUploader.drawWithShader(buffer.buildOrThrow());

		RenderSystem.lineWidth(1.0f);
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}
}

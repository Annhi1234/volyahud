package com.volyacraft.volyahud.client.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.volyacraft.volyahud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PearlTrajectory {

	// траєкторія у польоті кожної живої перли: id сутності -> точки її шляху
	private static final Map<Integer, List<Vec3>> TRAILS = new HashMap<>();

	public static void tick(Minecraft mc) {
		if (mc.level == null) return;
		if (!HudConfig.INSTANCE.pearlTrail) {
			TRAILS.clear();
			return;
		}

		// прибираємо перли, яких вже немає у світі
		TRAILS.keySet().removeIf(id -> mc.level.getEntity(id) == null);

		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof ThrownEnderpearl pearl) {
				TRAILS.computeIfAbsent(pearl.getId(), k -> new ArrayList<>()).add(pearl.position());
			}
		}
	}

	public static void render(WorldRenderContext context, Minecraft mc) {
		if (mc.player == null) return;
		HudConfig cfg = HudConfig.INSTANCE;
		Vec3 camPos = context.camera().getPosition();
		PoseStack matrices = context.matrixStack();
		MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

		matrices.pushPose();
		matrices.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f pose = matrices.last().pose();
		VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());

		// 1. Прев'ю траєкторії, поки тримаєш перлу в руці
		if (cfg.pearlTrajectory && isHoldingPearl(mc)) {
			List<Vec3> path = simulateThrow(mc);
			drawLine(buffer, pose, path, 1f, 1f, 1f, 0.85f);
		}

		// 2. Слід за вже кинутими перлами
		if (cfg.pearlTrail) {
			for (List<Vec3> trail : TRAILS.values()) {
				drawLine(buffer, pose, trail, 0.4f, 0.8f, 1f, 0.9f);
			}
		}

		bufferSource.endBatch(RenderType.lines());
		matrices.popPose();
	}

	private static boolean isHoldingPearl(Minecraft mc) {
		return mc.player.getMainHandItem().is(Items.ENDER_PEARL)
				|| mc.player.getOffhandItem().is(Items.ENDER_PEARL);
	}

	/**
	 * Проста симуляція фізики перли: гравітація 0.03/тік, опір повітря x0.99,
	 * початкова швидкість кидка ~1.5 (як у ванільному ProjectileUtil.getThrowPower).
	 */
	private static List<Vec3> simulateThrow(Minecraft mc) {
		List<Vec3> points = new ArrayList<>();
		Vec3 pos = mc.player.getEyePosition(1.0f);
		Vec3 look = mc.player.getViewVector(1.0f);
		Vec3 vel = look.scale(1.5);

		for (int i = 0; i < 120; i++) {
			pos = pos.add(vel);
			vel = vel.scale(0.99).subtract(0, 0.03, 0);
			points.add(pos);
			if (!mc.level.getBlockState(BlockPos.containing(pos)).isAir()) {
				break;
			}
		}
		return points;
	}

	private static void drawLine(VertexConsumer buffer, Matrix4f pose, List<Vec3> points, float r, float g, float b, float a) {
		if (points.size() < 2) return;
		for (int i = 0; i < points.size() - 1; i++) {
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
	}
}

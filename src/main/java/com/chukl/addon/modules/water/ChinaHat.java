package com.chukl.addon.modules.water;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline.Snippet;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.ModeSetting;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexConsumerProvider.Immediate;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public final class ChinaHat extends WModule {
   private static final int BUFFER_SIZE = 65536;
   private static final int SEGMENTS = 48;
   private static final RenderPipeline FILL_PIPELINE = RenderPipelines.register(
      RenderPipeline.builder(new Snippet[]{RenderPipelines.POSITION_COLOR_SNIPPET})
         .withLocation(Identifier.of("waterclient", "china_hat_fill"))
         .withVertexFormat(VertexFormats.POSITION_COLOR, DrawMode.TRIANGLES)
         .withCull(false)
         .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
         .withDepthWrite(true)
         .withBlend(BlendFunction.TRANSLUCENT)
         .build()
   );
   private static final RenderPipeline LINE_PIPELINE = RenderPipelines.register(
      RenderPipeline.builder(new Snippet[]{RenderPipelines.POSITION_COLOR_SNIPPET})
         .withLocation(Identifier.of("waterclient", "china_hat_line"))
         .withVertexFormat(VertexFormats.POSITION_COLOR, DrawMode.DEBUG_LINES)
         .withCull(false)
         .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
         .withDepthWrite(false)
         .withBlend(BlendFunction.LIGHTNING)
         .build()
   );
   private final ModeSetting mode = new ModeSetting("Mode", "China Hat", "China Hat", "Nimbus");
   private final Setting<Boolean> headAnchor = new Setting<>("Head Anchor", true);
   private final Setting<Color> color = new Setting<>("Color", new Color(32, 116, 255, 255));
   private final Setting<Double> radius = new Setting<>("Radius", 0.62, 0.35, 1.0);

   public ChinaHat() {
      super("China Hat", Category.RENDER);
      this.addSetting(this.mode);
      this.addSetting(this.headAnchor);
      this.addSetting(this.color);
      this.addSetting(this.radius);
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         if (mc.options.getPerspective() != Perspective.FIRST_PERSON) {
            Vec3d cameraPos = RenderUtils.getCameraPos(RenderUtils.getCamera());
            double x = mc.player.lastRenderX + (mc.player.getX() - mc.player.lastRenderX) * tickDelta;
            double y = mc.player.lastRenderY + (mc.player.getY() - mc.player.lastRenderY) * tickDelta;
            double z = mc.player.lastRenderZ + (mc.player.getZ() - mc.player.lastRenderZ) * tickDelta;
            double hatY = y + mc.player.getHeight() - (mc.player.isSneaking() ? 0.25 : 0.05);
            matrices.push();
            matrices.translate(x - cameraPos.x, hatY - cameraPos.y, z - cameraPos.z);
            if (this.headAnchor.getValue()) {
               float pitch = mc.player.getPitch(tickDelta);
               matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-mc.player.getYaw(tickDelta)));
               matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
               matrices.translate(0.0, Math.abs(pitch) / 90.0 * 0.3, 0.05);
            }

            BufferAllocator allocator = new BufferAllocator(65536);

            try {
               Immediate immediate = VertexConsumerProvider.immediate(allocator);
               Matrix4f matrix = matrices.peek().getPositionMatrix();
               if (this.mode.check("Nimbus")) {
                  this.renderNimbus(immediate, matrix);
               } else {
                  this.renderChinaHat(immediate, matrix);
               }

               immediate.draw();
            } catch (Throwable var16) {
               try {
                  allocator.close();
               } catch (Throwable var15) {
                  var16.addSuppressed(var15);
               }

               throw var16;
            }

            allocator.close();
            matrices.pop();
         }
      }
   }

   private void renderChinaHat(Immediate immediate, Matrix4f matrix) {
      float r = this.radius.getValue().floatValue();
      Color selected = this.color.getValue();
      VertexConsumer fill = immediate.getBuffer(fillLayer());

      for (int i = 0; i < 48; i++) {
         float a1 = (float)((Math.PI * 2) * i / 48.0);
         float a2 = (float)((Math.PI * 2) * (i + 1) / 48.0);
         int c1 = shade(selected, a1, 1.0F);
         int c2 = shade(selected, a2, 1.0F);
         fill.vertex(matrix, 0.0F, 0.38F, 0.0F).color(c1);
         fill.vertex(matrix, (float)Math.cos(a1) * r, 0.035F, (float)Math.sin(a1) * r).color(c1);
         fill.vertex(matrix, (float)Math.cos(a2) * r, 0.035F, (float)Math.sin(a2) * r).color(c2);
         int underside = shade(selected, a1, 0.62F);
         fill.vertex(matrix, 0.0F, 0.035F - 0.012F, 0.0F).color(underside);
         fill.vertex(matrix, (float)Math.cos(a2) * r, 0.035F - 0.012F, (float)Math.sin(a2) * r).color(underside);
         fill.vertex(matrix, (float)Math.cos(a1) * r, 0.035F - 0.012F, (float)Math.sin(a1) * r).color(underside);
      }

      VertexConsumer line = immediate.getBuffer(lineLayer());
      int lineColor = argb(new Color(selected.getRed(), selected.getGreen(), selected.getBlue(), 255));

      for (int i = 0; i < 48; i++) {
         float a1 = (float)((Math.PI * 2) * i / 48.0);
         float a2 = (float)((Math.PI * 2) * (i + 1) / 48.0);
         line.vertex(matrix, (float)Math.cos(a1) * r, 0.035F, (float)Math.sin(a1) * r).color(lineColor);
         line.vertex(matrix, (float)Math.cos(a2) * r, 0.035F, (float)Math.sin(a2) * r).color(lineColor);
      }
   }

   private void renderNimbus(Immediate immediate, Matrix4f matrix) {
      VertexConsumer line = immediate.getBuffer(lineLayer());
      Color selected = this.color.getValue();
      int lineColor = argb(new Color(selected.getRed(), selected.getGreen(), selected.getBlue(), 255));
      float r = Math.min(0.55F, this.radius.getValue().floatValue());

      for (int i = 0; i < 120; i++) {
         float a1 = (float)((Math.PI * 2) * i / 120.0);
         float a2 = (float)((Math.PI * 2) * (i + 1) / 120.0);
         line.vertex(matrix, (float)Math.cos(a1) * r, 0.1F, (float)Math.sin(a1) * r).color(lineColor);
         line.vertex(matrix, (float)Math.cos(a2) * r, 0.1F, (float)Math.sin(a2) * r).color(lineColor);
      }
   }

   private static int shade(Color base, float angle, float brightness) {
      float highlight = 0.72F + Math.max(0.0F, (float)Math.cos(angle - 0.65F)) * 0.28F;
      float factor = highlight * brightness;
      return argb(
         new Color(
            Math.min(255, Math.round(base.getRed() * factor)),
            Math.min(255, Math.round(base.getGreen() * factor)),
            Math.min(255, Math.round(base.getBlue() * factor)),
            255
         )
      );
   }

   private static int argb(Color c) {
      return c.getAlpha() << 24 | c.getRed() << 16 | c.getGreen() << 8 | c.getBlue();
   }

   private static RenderLayer fillLayer() {
      return RenderLayer.of("water_china_hat_fill", RenderSetup.builder(FILL_PIPELINE).expectedBufferSize(65536).translucent().build());
   }

   private static RenderLayer lineLayer() {
      return RenderLayer.of("water_china_hat_line", RenderSetup.builder(LINE_PIPELINE).expectedBufferSize(65536).translucent().build());
   }
}


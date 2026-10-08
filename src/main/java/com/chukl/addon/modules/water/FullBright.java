package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.option.SimpleOption;

public final class FullBright extends WModule {
   private static final double FULL_BRIGHT_GAMMA = 10.0;
   private double previousGamma = 1.0;
   private boolean gammaApplied = false;
   private static Field valueField;

   public FullBright() {
      super("FullBright", Category.RENDER);
   }

   @Override
   public void onEnable() {
      if (mc.options != null) {
         this.previousGamma = (Double)mc.options.getGamma().getValue();
         this.setGamma(10.0);
         this.gammaApplied = true;
      }
   }

   @Override
   public void onDisable() {
      if (mc.options != null) {
         this.setGamma(this.gammaApplied ? this.previousGamma : 1.0);
         this.gammaApplied = false;
      }
   }

   @Override
   public void onTick() {
      if (mc.options != null) {
         if (!this.gammaApplied) {
            this.previousGamma = (Double)mc.options.getGamma().getValue();
            if (Math.abs(this.previousGamma - 10.0) < 1.0E-4) {
               this.previousGamma = 1.0;
            }

            this.setGamma(10.0);
            this.gammaApplied = true;
         } else {
            try {
               double current = (Double)mc.options.getGamma().getValue();
               if (Math.abs(current - 10.0) > 1.0E-4) {
                  this.setGamma(10.0);
               }
            } catch (Exception var3) {
            }
         }
      }
   }

   private void setGamma(double gamma) {
      SimpleOption<Double> opt = mc.options.getGamma();
      Field field = valueField;
      if (field == null) {
         field = this.resolveValueField(opt);
         valueField = field;
      }

      if (field != null) {
         try {
            field.set(opt, gamma);
            Double applied = (Double)opt.getValue();
            if (applied != null && Math.abs(applied - gamma) <= 1.0E-4) {
               return;
            }

            valueField = null;
         } catch (Exception var7) {
         }
      }

      try {
         opt.setValue(gamma);
      } catch (Exception var6) {
      }
   }

   private Field resolveValueField(SimpleOption<?> option) {
      Object current;
      try {
         current = option.getValue();
      } catch (Exception var9) {
         current = null;
      }

      Field fallback = null;

      for (Field field : SimpleOption.class.getDeclaredFields()) {
         if (!Modifier.isStatic(field.getModifiers())) {
            if ("value".equals(field.getName())) {
               fallback = field;
            }

            field.setAccessible(true);

            try {
               Object value = field.get(option);
               if (current == null ? value == null : current.equals(value)) {
                  return field;
               }
            } catch (Exception var10) {
            }
         }
      }

      if (fallback != null) {
         fallback.setAccessible(true);
         return fallback;
      } else {
         return null;
      }
   }
}


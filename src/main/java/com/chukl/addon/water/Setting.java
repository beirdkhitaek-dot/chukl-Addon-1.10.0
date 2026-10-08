package com.chukl.addon.water;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.awt.Color;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Water-style setting (getValue/setValue/visibleWhen) that is backed by a real Meteor setting once the
 * module registers it, so it shows up in Meteor's GUI and is saved in your Meteor config.
 */
public class Setting<T> {
    private final String name;
    private final T defaultValue;
    private final T min;
    private final T max;
    private T value;
    private Supplier<Boolean> visibility = () -> true;

    private Supplier<T> getter;
    private Consumer<T> setter;

    public Setting(String name, T value) {
        this(name, value, null, null);
    }

    public Setting(String name, T value, T min, T max) {
        this.name = name;
        this.value = value;
        this.defaultValue = value;
        this.min = min;
        this.max = max;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return getter != null ? getter.get() : value;
    }

    public void setValue(T value) {
        this.value = value;
        if (setter != null) setter.accept(value);
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public T getMin() {
        return min;
    }

    public T getMax() {
        return max;
    }

    public boolean matchesName(String settingName) {
        return name.equalsIgnoreCase(settingName);
    }

    public Setting<T> visibleWhen(Supplier<Boolean> visibility) {
        this.visibility = visibility == null ? () -> true : visibility;
        return this;
    }

    public boolean isVisible() {
        try {
            return visibility.get();
        } catch (Exception e) {
            return true;
        }
    }

    protected final void bind(Supplier<T> getter, Consumer<T> setter) {
        this.getter = getter;
        this.setter = setter;
    }

    static String kebab(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "setting" : s;
    }

    /** Creates the Meteor setting that stores this value. */
    @SuppressWarnings("unchecked")
    protected void attach(SettingGroup group, String id) {
        if (defaultValue instanceof Boolean b) {
            BoolSetting s = group.add(new BoolSetting.Builder().name(id).description(name)
                .defaultValue(b).visible(this::isVisible).build());
            bind(() -> (T) (Boolean) s.get(), v -> s.set((Boolean) v));

        } else if (defaultValue instanceof Integer i) {
            int lo = min instanceof Number n ? n.intValue() : Math.min(0, i);
            int hi = max instanceof Number n ? n.intValue() : Math.max(100, Math.abs(i) * 10);
            IntSetting s = group.add(new IntSetting.Builder().name(id).description(name)
                .defaultValue(i).range(lo, hi).sliderRange(lo, hi).visible(this::isVisible).build());
            bind(() -> (T) (Integer) s.get(), v -> s.set(((Number) v).intValue()));

        } else if (defaultValue instanceof Double || defaultValue instanceof Float) {
            boolean isFloat = defaultValue instanceof Float;
            double d = ((Number) defaultValue).doubleValue();
            double lo = min instanceof Number n ? n.doubleValue() : Math.min(0, d);
            double hi = max instanceof Number n ? n.doubleValue() : Math.max(100, Math.abs(d) * 10);
            DoubleSetting s = group.add(new DoubleSetting.Builder().name(id).description(name)
                .defaultValue(d).range(lo, hi).sliderRange(lo, hi).visible(this::isVisible).build());
            if (isFloat) bind(() -> (T) (Float) (float) (double) s.get(), v -> s.set(((Number) v).doubleValue()));
            else bind(() -> (T) (Double) s.get(), v -> s.set(((Number) v).doubleValue()));

        } else if (defaultValue instanceof Color c) {
            ColorSetting s = group.add(new ColorSetting.Builder().name(id).description(name)
                .defaultValue(new SettingColor(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()))
                .visible(this::isVisible).build());
            bind(() -> {
                SettingColor sc = s.get();
                return (T) new Color(sc.r, sc.g, sc.b, sc.a);
            }, v -> {
                Color nc = (Color) v;
                s.set(new SettingColor(nc.getRed(), nc.getGreen(), nc.getBlue(), nc.getAlpha()));
            });

        } else if (defaultValue instanceof String str) {
            StringSetting s = group.add(new StringSetting.Builder().name(id).description(name)
                .defaultValue(str).visible(this::isVisible).build());
            bind(() -> (T) s.get(), v -> s.set((String) v));
        }
        // Anything else stays a plain in-memory value
    }
}

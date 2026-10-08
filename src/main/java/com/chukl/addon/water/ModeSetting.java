package com.chukl.addon.water;

import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import java.util.List;

public final class ModeSetting extends Setting<String> {
    private final List<String> modes;

    public ModeSetting(String name, String defaultValue, String... modes) {
        this(name, defaultValue, new String[0], modes);
    }

    public ModeSetting(String name, String defaultValue, String[] legacyNames, String... modes) {
        super(name, defaultValue);
        if (modes == null || modes.length == 0) throw new IllegalArgumentException("ModeSetting requires at least one mode");
        this.modes = List.of(modes);
    }

    public List<String> getModes() {
        return modes;
    }

    public boolean check(String mode) {
        return mode != null && mode.trim().equalsIgnoreCase(getValue());
    }

    @Override
    public String getValue() {
        return resolve(super.getValue());
    }

    @Override
    public void setValue(String value) {
        super.setValue(resolve(value));
    }

    private String resolve(String value) {
        if (value != null) {
            for (String m : modes) if (m.equalsIgnoreCase(value.trim())) return m;
        }
        return modes.get(0);
    }

    @Override
    protected void attach(SettingGroup group, String id) {
        ProvidedStringSetting s = group.add(new ProvidedStringSetting.Builder().name(id)
            .description(getName() + " (" + String.join(", ", modes) + ")")
            .defaultValue(getDefaultValue()).supplier(() -> modes.toArray(new String[0]))
            .visible(this::isVisible).build());
        bind(() -> resolve(s.get()), v -> s.set(resolve(v)));
    }
}

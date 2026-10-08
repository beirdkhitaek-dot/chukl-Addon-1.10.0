package com.chukl.addon.water;

import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.entity.EntityType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MobsSetting extends Setting<Set<EntityType<?>>> {
    private long version;
    private int lastHash = Integer.MIN_VALUE;

    public MobsSetting(String name, EntityType<?>... defaults) {
        super(name, defaultSet(defaults));
    }

    private static Set<EntityType<?>> defaultSet(EntityType<?>[] defaults) {
        LinkedHashSet<EntityType<?>> set = new LinkedHashSet<>();
        if (defaults != null) Collections.addAll(set, defaults);
        set.remove(null);
        return set;
    }

    @Override
    protected void attach(SettingGroup group, String id) {
        EntityTypeListSetting s = group.add(new EntityTypeListSetting.Builder().name(id).description(getName())
            .defaultValue(getDefaultValue().toArray(new EntityType<?>[0])).visible(this::isVisible).build());
        bind(() -> new LinkedHashSet<>(s.get()), v -> s.set(new LinkedHashSet<>(v)));
    }

    public boolean contains(EntityType<?> type) {
        return type != null && getValue().contains(type);
    }

    public void toggle(EntityType<?> type) {
        if (type == null) return;
        LinkedHashSet<EntityType<?>> next = new LinkedHashSet<>(getValue());
        if (!next.add(type)) next.remove(type);
        setValue(next);
    }

    public void clear() {
        setValue(new LinkedHashSet<>());
    }

    public int size() {
        return getValue().size();
    }

    public long getVersion() {
        int hash = getValue().hashCode();
        if (hash != lastHash) {
            lastHash = hash;
            version++;
        }
        return version;
    }

    public Set<EntityType<?>> getSelectedMobs() {
        return Collections.unmodifiableSet(getValue());
    }
}

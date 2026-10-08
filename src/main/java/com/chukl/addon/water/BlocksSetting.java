package com.chukl.addon.water;

import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class BlocksSetting extends Setting<Set<Block>> {
    private long version;
    private int lastHash = Integer.MIN_VALUE;

    public BlocksSetting(String name, Block... defaults) {
        super(name, defaultSet(defaults));
    }

    private static Set<Block> defaultSet(Block[] defaults) {
        LinkedHashSet<Block> set = new LinkedHashSet<>();
        if (defaults != null) Collections.addAll(set, defaults);
        set.remove(null);
        set.remove(Blocks.AIR);
        return set;
    }

    @Override
    protected void attach(SettingGroup group, String id) {
        BlockListSetting s = group.add(new BlockListSetting.Builder().name(id).description(getName())
            .defaultValue(getDefaultValue().toArray(new Block[0])).visible(this::isVisible).build());
        bind(() -> new LinkedHashSet<>(s.get()), v -> s.set(new ArrayList<>(v)));
    }

    public boolean contains(Block block) {
        return block != null && getValue().contains(block);
    }

    public void toggle(Block block) {
        if (block == null || block == Blocks.AIR) return;
        LinkedHashSet<Block> next = new LinkedHashSet<>(getValue());
        if (!next.add(block)) next.remove(block);
        setValue(next);
    }

    public void clear() {
        setValue(new LinkedHashSet<>());
    }

    public int size() {
        return getValue().size();
    }

    public boolean isEmpty() {
        return getValue().isEmpty();
    }

    /** Changes whenever the selected blocks change (modules use it to know when to rescan). */
    public long getVersion() {
        int hash = getValue().hashCode();
        if (hash != lastHash) {
            lastHash = hash;
            version++;
        }
        return version;
    }

    public Set<Block> getSelectedBlocks() {
        return Collections.unmodifiableSet(getValue());
    }
}

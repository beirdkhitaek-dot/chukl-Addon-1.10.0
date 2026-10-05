package com.chukl.addon;

import com.chukl.addon.modules.DeepBlockEntityEsp;
import com.chukl.addon.modules.DugOutHighlighter;
import com.chukl.addon.modules.LightFinder;
import com.chukl.addon.modules.SusChunkFinder;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ChuklAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Chukl");

    @Override
    public void onInitialize() {
        LOG.info("Initializing ChuklAddon");

        Modules.get().add(new DeepBlockEntityEsp());
        Modules.get().add(new DugOutHighlighter());
        Modules.get().add(new SusChunkFinder());
        Modules.get().add(new LightFinder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.chukl.addon";
    }
}

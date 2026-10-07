package com.chukl.addon.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Input.class)
public interface InputAccessor {
    @Mutable
    @Accessor("playerInput")
    void chukl$setPlayerInput(PlayerInput input);

    @Mutable
    @Accessor("movementVector")
    void chukl$setMovementVector(Vec2f vector);
}

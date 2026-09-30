package com.axalotl.async.common.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {"dev.latvian.mods.rhino.Context", "dev.latvian.mods.rhino.InterpretedFunction",
        "dev.latvian.mods.rhino.InterfaceAdapter"}, remap = false)
public abstract class RhinoContextMixin {}

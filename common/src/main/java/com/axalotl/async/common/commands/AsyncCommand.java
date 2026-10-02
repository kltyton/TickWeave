package com.axalotl.async.common.commands;

import com.axalotl.async.common.commands.ConfigCommand;
import com.axalotl.async.common.commands.StatsCommand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

public class AsyncCommand {
    public static final Component prefix = Component.literal("\u00a78[\u00a7fTickWeave\u00a78]\u00a77 ");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> main = Commands.literal("tickweave");
        main = ConfigCommand.registerConfig(main);
        main = StatsCommand.registerStatus(main);
        dispatcher.register(main);
    }

    public static Registry<EntityType<?>> getEntityAccess(CommandSourceStack source) {
        return source.registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
    }
}

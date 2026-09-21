package com.axalotl.async.common.chunk;

import net.minecraft.world.level.chunk.ChunkStatus;

public record ChunkRequest(long position, ChunkStatus status, boolean create) {}

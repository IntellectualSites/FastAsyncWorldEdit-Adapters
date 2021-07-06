package com.sk89q.worldedit.bukkit.adapter.impl;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.nbt.CompoundBinaryTag;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.storage.ChunkStore;
import net.minecraft.server.v1_16_R3.Block;
import net.minecraft.server.v1_16_R3.BlockPosition;
import net.minecraft.server.v1_16_R3.Chunk;
import net.minecraft.server.v1_16_R3.ChunkProviderServer;
import net.minecraft.server.v1_16_R3.EnumDirection;
import net.minecraft.server.v1_16_R3.GeneratorAccess;
import net.minecraft.server.v1_16_R3.IBlockData;
import net.minecraft.server.v1_16_R3.NBTBase;
import net.minecraft.server.v1_16_R3.NBTTagCompound;
import net.minecraft.server.v1_16_R3.PlayerChunk;
import net.minecraft.server.v1_16_R3.TileEntity;
import net.minecraft.server.v1_16_R3.World;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.block.data.CraftBlockData;
import org.bukkit.event.block.BlockPhysicsEvent;

import java.lang.ref.WeakReference;
import java.util.Objects;
import javax.annotation.Nullable;

public class WorldNativeAccess_v1_16_R3 implements WorldNativeAccess<Chunk, IBlockData, BlockPosition> {
    private static final int UPDATE = 1, NOTIFY = 2;

    private final Spigot_v1_16_R3 adapter;
    private final WeakReference<World> world;
    private SideEffectSet sideEffectSet;

    public WorldNativeAccess_v1_16_R3(Spigot_v1_16_R3 adapter, WeakReference<World> world) {
        this.adapter = adapter;
        this.world = world;
    }

    private World getWorld() {
        return Objects.requireNonNull(world.get(), "The reference to the world was lost");
    }

    @Override
    public void setCurrentSideEffectSet(SideEffectSet sideEffectSet) {
        this.sideEffectSet = sideEffectSet;
    }

    @Override
    public Chunk getChunk(int x, int z) {
        return getWorld().getChunkAt(x, z);
    }

    @Override
    public IBlockData toNative(BlockState state) {
        int stateId = BlockStateIdAccess.getBlockStateId(state);
        return BlockStateIdAccess.isValidInternalId(stateId)
            ? Block.getByCombinedId(stateId)
            : ((CraftBlockData) BukkitAdapter.adapt(state)).getState();
    }

    @Override
    public IBlockData getBlockState(Chunk chunk, BlockPosition position) {
        return chunk.getType(position);
    }

    @Nullable
    @Override
    public IBlockData setBlockState(Chunk chunk, BlockPosition position, IBlockData state) {
        return chunk.setType(position, state, false, this.sideEffectSet.shouldApply(SideEffect.UPDATE));
    }

    @Override
    public IBlockData getValidBlockForPosition(IBlockData block, BlockPosition position) {
        return Block.b(block, getWorld(), position);
    }

    @Override
    public BlockPosition getPosition(int x, int y, int z) {
        return new BlockPosition(x, y, z);
    }

    @Override
    public void updateLightingForBlock(BlockPosition position) {
        getWorld().getChunkProvider().getLightEngine().a(position);
    }

    @Override
    public boolean updateTileEntity(BlockPosition position, CompoundBinaryTag tag) {
        // We will assume that the tile entity was created for us,
        // though we do not do this on the other versions
        TileEntity tileEntity = getWorld().getTileEntity(position);
        if (tileEntity == null) {
            return false;
        }
        NBTBase nativeTag = adapter.fromNativeBinary(tag);
        Spigot_v1_16_R3.readTagIntoTileEntity((NBTTagCompound) nativeTag, tileEntity);
        return true;
    }

    @Override
    public void notifyBlockUpdate(Chunk chunk, BlockPosition position, IBlockData oldState, IBlockData newState) {
        if (chunk.getSections()[position.getY() >> ChunkStore.CHUNK_SHIFTS] != null) {
            getWorld().notify(position, oldState, newState, UPDATE | NOTIFY);
        }
    }

    @Override
    public boolean isChunkTicking(Chunk chunk) {
        return chunk.getState().isAtLeast(PlayerChunk.State.TICKING);
    }

    @Override
    public void markBlockChanged(Chunk chunk, BlockPosition position) {
        if (chunk.getSections()[position.getY() >> ChunkStore.CHUNK_SHIFTS] != null) {
            ((ChunkProviderServer) getWorld().getChunkProvider()).flagDirty(position);
        }
    }

    private static final EnumDirection[] NEIGHBOUR_ORDER = {
        EnumDirection.WEST, EnumDirection.EAST,
        EnumDirection.DOWN, EnumDirection.UP,
        EnumDirection.NORTH, EnumDirection.SOUTH
    };

    @Override
    public void notifyNeighbors(BlockPosition pos, IBlockData oldState, IBlockData newState) {
        World world = getWorld();
        if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
            world.update(pos, oldState.getBlock());
        } else {
            // When we don't want events, manually run the physics without them.
            // Un-nest neighbour updating
            for (EnumDirection direction : NEIGHBOUR_ORDER) {
                BlockPosition shifted = pos.shift(direction);
                world.getType(shifted).doPhysics(world, shifted, oldState.getBlock(), pos, false);
            }
        }
        if (newState.isComplexRedstone()) {
            world.updateAdjacentComparators(pos, newState.getBlock());
        }
    }

    @Override
    public void updateNeighbors(BlockPosition pos, IBlockData oldState, IBlockData newState, int recursionLimit) {
        World world = getWorld();
        // a == updateNeighbors
        // b == updateDiagonalNeighbors
        oldState.b(world, pos, NOTIFY, recursionLimit);
        if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
            CraftWorld craftWorld = world.getWorld();
            if (craftWorld != null) {
                BlockPhysicsEvent event = new BlockPhysicsEvent(craftWorld.getBlockAt(pos.getX(), pos.getY(), pos.getZ()), CraftBlockData.fromData(newState));
                world.getServer().getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return;
                }
            }
        }
        newState.a((GeneratorAccess) world, pos, NOTIFY, recursionLimit);
        newState.b(world, pos, NOTIFY, recursionLimit);
    }

    @Override
    public void onBlockStateChange(BlockPosition pos, IBlockData oldState, IBlockData newState) {
        getWorld().a(pos, oldState, newState);
    }

    @Override
    public void flush() {

    }
}

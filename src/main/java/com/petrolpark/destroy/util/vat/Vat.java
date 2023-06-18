package com.petrolpark.destroy.util.vat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.petrolpark.destroy.Destroy;
import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.utility.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class Vat {

    public static final int MB_PER_BLOCK = 4000;

    private BlockPos lowerCorner;
    private BlockPos upperCorner;

    private ImmutableList<BlockPos> sides; // NOT synced server/client

    public Vat(BlockPos lowerCorner, BlockPos upperCorner) {
        this.lowerCorner = lowerCorner;
        this.upperCorner = upperCorner;
    };

    /**
     * 
     * @param level
     * @param pos The position of the first space in the Vat
     */
    public static Optional<Vat> tryConstruct(Level level, BlockPos pos) {

        boolean successful = true;

        EnumMap<Direction, Integer> dimensions = new EnumMap<>(Direction.class) {
            @Override
            public Integer get(Object key) {
                Integer value = super.get(key);
                if (value == null) {
                    return 0;
                } else {
                    return value;
                }
            };
        };

        // Expand the dimenions of the vat in each direction as far as possible
        tryExpandInDirection: for (Direction direction : Direction.values()) {

            while (true) {

                // Don't expand more than a diameter of 5 blocks
                if (dimensions.get(direction) + dimensions.get(direction.getOpposite()) > 5) {
                    successful = false;
                    break tryExpandInDirection;
                };
                
                // Add a new one Block thick plane on and see if its all valid
                BlockPos newPlaneCentre = new BlockPos(pos).relative(direction, 1 + dimensions.get(direction));

                // We're defining the plane with two opposing corners
                BlockPos lowerCorner = new BlockPos(newPlaneCentre);
                BlockPos upperCorner = new BlockPos(newPlaneCentre);

                // Scale the plane perpendicular to the direction we're trying to expand in
                tryAddNewPlane: for (Direction secondaryDirection : Direction.values()) {
                    if (direction.getAxis() == secondaryDirection.getAxis()) continue tryAddNewPlane;
                    if (secondaryDirection.getAxisDirection() == AxisDirection.POSITIVE) {
                        upperCorner = upperCorner.relative(secondaryDirection, dimensions.get(secondaryDirection));
                    } else {
                        lowerCorner = lowerCorner.relative(secondaryDirection, dimensions.get(secondaryDirection));
                    };
                };

                // Now check all Blocks in the plane
                boolean allAir = true;
                boolean allWalls = true;
                for (BlockPos blockPos : BlockPos.betweenClosed(lowerCorner, upperCorner)) {
                    BlockState state = level.getBlockState(blockPos);
                    if (state.isAir()) {
                        allWalls = false;
                    } else {
                        allAir = false;
                        if (!VatMaterial.isValid(state.getBlock())) allWalls = false;
                    }
                };

                // If we have reached a boundary where everything is a wall
                if (allWalls) {
                    continue tryExpandInDirection;
                // If we have reached an illegal block
                } else if (!allAir) {
                    successful = false;
                    break tryExpandInDirection;
                };

                // Increment the value in this direction
                dimensions.merge(direction, 1, (i1, i2) -> i1 + 1);
            }
        };

        if (!successful) return Optional.empty();

        // Determine the precise location of the Vat, inflated by 1 in each direction so it includes the walls
        int topSide = pos.getY() + 1 + dimensions.get(Direction.UP);
        int northSide = pos.getZ() - 1 - dimensions.get(Direction.NORTH);
        int eastSide = pos.getX() + 1 + dimensions.get(Direction.EAST);
        int southSide = pos.getZ() + 1 + dimensions.get(Direction.SOUTH);
        int westSide = pos.getX() - 1 - dimensions.get(Direction.WEST);
        int bottomSide = pos.getY() - 1 - dimensions.get(Direction.DOWN);
        BlockPos lowerCorner = new BlockPos(westSide, bottomSide, northSide);
        BlockPos upperCorner = new BlockPos(eastSide, topSide, southSide);

        List<BlockPos> sides = new ArrayList<>();

        for (BlockPos blockPos : BlockPos.betweenClosed(lowerCorner, upperCorner)) {
            int x = blockPos.getX();
            int y = blockPos.getY();
            int z = blockPos.getZ();
            // Check all blocks which form a face of the Vat, but aren't an edge or corner

            boolean onXSide = (x == eastSide || x == westSide); // A
            boolean onYSide = (y == topSide || y == bottomSide); // B
            boolean onZSide = (z == northSide || z == southSide); // C
            /*
             * Check all sides which are on a face, but are not an edge or corner.
             * A B C Output
             * 0 0 0 0
             * 0 0 1 1
             * 0 1 0 1
             * 0 1 1 0
             * 1 0 0 1
             * 1 0 1 0
             * 1 1 0 0
             * 1 1 1 0
             */
            if (((onXSide ^ onYSide) ^ onZSide) && !(onXSide && onYSide)) {
                if (!VatMaterial.isValid(level.getBlockState(blockPos).getBlock())) {
                    successful = false;
                    break;
                };
                sides.add(new BlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
            };
        };

        if (successful) {
            Vat vat = new Vat(lowerCorner, upperCorner);
            vat.sides = ImmutableList.copyOf(sides);
            Destroy.LOGGER.info("there are this many sides blocks: "+sides.size());
            return Optional.of(vat);
        } else {
            return Optional.empty();
        }
    };

    public int getCapacity() {
        return (upperCorner.getX() - getInternalLowerCorner().getX())
            * (getInternalHeight())
            * (upperCorner.getZ() - getInternalLowerCorner().getZ())
            * MB_PER_BLOCK;
    };

    public BlockPos getLowerCorner() {
        return lowerCorner;
    };

    public BlockPos getInternalLowerCorner() {
        return lowerCorner.above().east().south();
    };

    public BlockPos getUpperCorner() {
        return upperCorner;
    };

    public BlockPos getInternalUpperCorner() {
        return upperCorner.below().west().north();
    };

    public int getInternalHeight() {
        return upperCorner.getY() - getInternalLowerCorner().getY();
    };

    public Collection<BlockPos> getSideBlockPositions() {
        if (this.sides == null) {
            List<BlockPos> newSides = new ArrayList<>();
            for (BlockPos blockPos : BlockPos.betweenClosed(lowerCorner, upperCorner)) {
                int x = blockPos.getX();
                int y = blockPos.getY();
                int z = blockPos.getZ();
                // Check all blocks which form a face of the Vat, but aren't an edge or corner
    
                boolean onXSide = (x == lowerCorner.getX() || x == upperCorner.getX());
                boolean onYSide = (y == lowerCorner.getY() || y == upperCorner.getY());
                boolean onZSide = (z == lowerCorner.getZ() || z == upperCorner.getZ());

                if (((onXSide ^ onYSide) ^ onZSide) && !(onXSide && onYSide)) {
                    newSides.add(new BlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
                };
            };
            Destroy.LOGGER.info("there are this many sides blocks: "+newSides.size());
            sides = ImmutableList.copyOf(newSides);
        };
        return sides;
    };

    @OnlyIn(Dist.CLIENT)
    public void showBoundingBox() {
        CreateClient.OUTLINER.showAABB(Pair.of("outliner", lowerCorner), new AABB(lowerCorner, upperCorner), 100)
            .colored(0xFF_ebd31e);
    };
};

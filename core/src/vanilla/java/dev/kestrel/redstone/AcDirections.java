package dev.kestrel.redstone;

import net.minecraft.core.Direction;

/**
 * Cardinal/vertical direction indexing for the Alternate Current port.
 *
 * <p>Transcribed from {@code alternate.current.wire.WireHandler$Directions}
 * (SpaceWalkerRS, MIT): cardinal directions ordered clockwise so relative
 * turns are arithmetic ({@code (iDir + 1) & 0b11} is right,
 * {@code +2} is back, {@code +3} is left).
 */
public final class AcDirections {
    public static final Direction[] ALL =
            {Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.DOWN, Direction.UP};
    public static final Direction[] HORIZONTAL =
            {Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH};

    public static final int WEST = 0;
    public static final int NORTH = 1;
    public static final int EAST = 2;
    public static final int SOUTH = 3;
    public static final int DOWN = 4;
    public static final int UP = 5;

    private AcDirections() {
    }

    /** Transcribed from AC {@code Directions.iOpposite}. */
    public static int opposite(int iDir) {
        return iDir ^ (0b10 >>> (iDir >>> 2));
    }

    /** Transcribed from AC {@code Directions.index}. */
    public static int index(Direction dir) {
        for (int i = 0; i < ALL.length; i++) {
            if (dir == ALL[i]) {
                return i;
            }
        }
        return -1;
    }
}

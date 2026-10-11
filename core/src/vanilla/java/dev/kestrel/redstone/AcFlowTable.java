package dev.kestrel.redstone;

/**
 * Incoming-flow to outgoing-flow conversion table for the Alternate Current
 * port.
 *
 * <p>Transcribed verbatim (values and documented rule) from
 * {@code alternate.current.wire.WireHandler.FLOW_IN_TO_FLOW_OUT}
 * (SpaceWalkerRS, MIT). Input is a 4-bit number encoding incoming flow per
 * cardinal direction; output is a single direction index or -1 for ambiguous
 * cases. Rule: single input wins; two non-opposing inputs resolve to the
 * more-clockwise one; three inputs cancel the opposing pair; anything else
 * is ambiguous.
 */
public final class AcFlowTable {
    private AcFlowTable() {
    }

    static final int[] FLOW_IN_TO_FLOW_OUT = {
        -1, // 0b0000: none -> ambiguous
        AcDirections.WEST, // 0b0001: west -> west
        AcDirections.NORTH, // 0b0010: north -> north
        AcDirections.NORTH, // 0b0011: west/north -> north
        AcDirections.EAST, // 0b0100: east -> east
        -1, // 0b0101: west/east -> ambiguous
        AcDirections.EAST, // 0b0110: north/east -> east
        AcDirections.NORTH, // 0b0111: west/north/east -> north
        AcDirections.SOUTH, // 0b1000: south -> south
        AcDirections.WEST, // 0b1001: west/south -> west
        -1, // 0b1010: north/south -> ambiguous
        AcDirections.WEST, // 0b1011: west/north/south -> west
        AcDirections.SOUTH, // 0b1100: east/south -> south
        AcDirections.SOUTH, // 0b1101: west/east/south -> south
        AcDirections.EAST, // 0b1110: north/east/south -> east
        -1, // 0b1111: all -> ambiguous
    };

    /** Shape-update order matching vanilla (AC {@code SHAPE_UPDATE_ORDER}). */
    public static final int[] SHAPE_UPDATE_ORDER = {
        AcDirections.WEST, AcDirections.EAST, AcDirections.NORTH,
        AcDirections.SOUTH, AcDirections.DOWN, AcDirections.UP,
    };

    public static int flowOut(int flowIn) {
        return FLOW_IN_TO_FLOW_OUT[flowIn & 0xF];
    }
}

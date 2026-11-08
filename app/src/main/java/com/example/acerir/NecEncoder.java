package com.example.acerir;

/**
 * Builds the on/off pulse pattern (microseconds, starting with a mark) for one NEC frame.
 *
 * Standard NEC: 8-bit address + its inverse, 8-bit command + its inverse.
 * Extended NEC: 16-bit address (no inverse), 8-bit command + its inverse.
 * All bytes are sent least-significant bit first, carrier 38 kHz.
 */
public final class NecEncoder {

    private static final int HEADER_MARK = 9000;
    private static final int HEADER_SPACE = 4500;
    private static final int BIT_MARK = 560;
    private static final int ZERO_SPACE = 560;
    private static final int ONE_SPACE = 1690;

    private NecEncoder() {}

    /** The 32 raw bits as an int, first transmitted bit = least significant bit. */
    public static int frame(int address, int command, boolean extended) {
        int low = address & 0xFF;
        int high = extended ? (address >> 8) & 0xFF : (~address) & 0xFF;
        int cmd = command & 0xFF;
        int cmdInv = (~command) & 0xFF;
        return low | (high << 8) | (cmd << 16) | (cmdInv << 24);
    }

    public static int[] encode(int address, int command, boolean extended) {
        int data = frame(address, command, extended);
        int[] pattern = new int[2 + 32 * 2 + 1];
        int i = 0;
        pattern[i++] = HEADER_MARK;
        pattern[i++] = HEADER_SPACE;
        for (int bit = 0; bit < 32; bit++) {
            pattern[i++] = BIT_MARK;
            pattern[i++] = ((data >>> bit) & 1) == 1 ? ONE_SPACE : ZERO_SPACE;
        }
        pattern[i] = BIT_MARK; // stop mark
        return pattern;
    }
}

package pl.skyblock.miniony.util;

/** Klucze chunków i pozycji używane w indeksach. */
public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public static long chunkKeyOfBlock(double x, double z) {
        return chunkKey(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }

    /**
     * Pozycja bloku spakowana do long (x, z: 26 bitów, y: 12 bitów - zakres jak w Minecraft).
     */
    public static long packBlock(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}

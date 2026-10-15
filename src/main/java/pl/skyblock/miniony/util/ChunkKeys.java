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

    public static String blockKey(String world, int x, int y, int z) {
        return world + ';' + x + ';' + y + ';' + z;
    }
}

package pl.skyblock.miniony.util;

/** Niezmienna pozycja bloku (bez świata). */
public record BlockPos(int x, int y, int z) {

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }
}

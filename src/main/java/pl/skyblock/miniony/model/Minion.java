package pl.skyblock.miniony.model;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.TextDisplay;
import pl.skyblock.miniony.util.BlockPos;
import pl.skyblock.miniony.util.ChunkKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minion. Pola trwałe są zapisywane w bazie, pola runtime (encje, status, harmonogram) żyją tylko w pamięci.
 * Klasa nie jest bezpieczna wątkowo - używana wyłącznie na głównym wątku serwera.
 */
public final class Minion {

    private final UUID id;
    private UUID owner;
    private String ownerName;
    private UUID islandId;
    private String typeId;
    private String rarity;
    private int level;
    private MinionState state;
    private UUID token;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private MinionStorage storage;
    private final List<String> modules = new ArrayList<>();
    private boolean pendingDelivery;
    private final long createdAt;
    private long lastWorkAt;

    // --- runtime ---
    private ArmorStand body;
    private TextDisplay hologram;
    private long nextWorkAt;
    private MinionStatus status = MinionStatus.IDLE;
    private List<BlockPos> area;
    private String hologramCache;
    private boolean dirty;

    public Minion(UUID id, UUID owner, String ownerName, String typeId, String rarity, int level,
                  MinionStorage storage, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.typeId = typeId;
        this.rarity = rarity;
        this.level = level;
        this.storage = storage;
        this.createdAt = createdAt;
        this.state = MinionState.ITEM;
        this.token = UUID.randomUUID();
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public String ownerName() {
        return ownerName == null ? "?" : ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public UUID islandId() {
        return islandId;
    }

    public void setIslandId(UUID islandId) {
        this.islandId = islandId;
    }

    public String typeId() {
        return typeId;
    }

    public void setTypeId(String typeId) {
        this.typeId = typeId;
    }

    public String rarity() {
        return rarity;
    }

    public void setRarity(String rarity) {
        this.rarity = rarity;
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public MinionState state() {
        return state;
    }

    public void setState(MinionState state) {
        this.state = state;
    }

    public UUID token() {
        return token;
    }

    /** Nadaje nowy token - wszystkie wcześniejsze przedmioty tego miniona stają się nieważne. */
    public UUID rotateToken() {
        this.token = UUID.randomUUID();
        return token;
    }

    public void setToken(UUID token) {
        this.token = token;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public long chunkKey() {
        return ChunkKeys.chunkKeyOfBlock(x, z);
    }

    public int chunkX() {
        return blockX() >> 4;
    }

    public int chunkZ() {
        return blockZ() >> 4;
    }

    public String blockKey() {
        return ChunkKeys.blockKey(world, blockX(), blockY(), blockZ());
    }

    public void setLocation(String world, double x, double y, double z, float yaw) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.area = null;
    }

    public void clearLocation() {
        this.world = null;
        this.area = null;
    }

    public MinionStorage storage() {
        return storage;
    }

    public void setStorage(MinionStorage storage) {
        this.storage = storage;
    }

    public List<String> modules() {
        return modules;
    }

    public boolean pendingDelivery() {
        return pendingDelivery;
    }

    public void setPendingDelivery(boolean pendingDelivery) {
        this.pendingDelivery = pendingDelivery;
    }

    public long createdAt() {
        return createdAt;
    }

    public long lastWorkAt() {
        return lastWorkAt;
    }

    public void setLastWorkAt(long lastWorkAt) {
        this.lastWorkAt = lastWorkAt;
    }

    public ArmorStand body() {
        return body;
    }

    public void setBody(ArmorStand body) {
        this.body = body;
    }

    public TextDisplay hologram() {
        return hologram;
    }

    public void setHologram(TextDisplay hologram) {
        this.hologram = hologram;
    }

    public long nextWorkAt() {
        return nextWorkAt;
    }

    public void setNextWorkAt(long nextWorkAt) {
        this.nextWorkAt = nextWorkAt;
    }

    public MinionStatus status() {
        return status;
    }

    public void setStatus(MinionStatus status) {
        this.status = status;
    }

    public List<BlockPos> area() {
        return area;
    }

    public void setArea(List<BlockPos> area) {
        this.area = area;
    }

    public String hologramCache() {
        return hologramCache;
    }

    public void setHologramCache(String hologramCache) {
        this.hologramCache = hologramCache;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}

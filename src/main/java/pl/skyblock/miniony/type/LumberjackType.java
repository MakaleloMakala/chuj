package pl.skyblock.miniony.type;

import org.bukkit.Color;
import org.bukkit.Material;
import pl.skyblock.miniony.config.Appearance;

/** Drwal: na swoim poziomie stawia pnie (na stałym gruncie) i ścina je do magazynu. */
public final class LumberjackType extends BlockCycleType {

    public static final String ID = "drwal";

    public LumberjackType() {
        super(ID, "Drwal", new Appearance(Material.WOODEN_AXE, Color.fromRGB(0x6B4423), ""), Material.OAK_LOG);
    }

    @Override
    protected boolean requiresGround() {
        return true;
    }

    @Override
    public int areaYOffset() {
        return 0;
    }
}

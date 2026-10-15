package pl.skyblock.miniony.type;

import org.bukkit.Color;
import org.bukkit.Material;
import pl.skyblock.miniony.config.Appearance;

/** Górnik: stawia i wykopuje blok (domyślnie bruk) na warstwie pod sobą. */
public final class MinerType extends BlockCycleType {

    public static final String ID = "gornik";

    public MinerType() {
        super(ID, "Górnik", new Appearance(Material.WOODEN_PICKAXE, Color.fromRGB(0x7F7F7F), ""), Material.COBBLESTONE);
    }

    @Override
    protected boolean requiresGround() {
        return false;
    }
}

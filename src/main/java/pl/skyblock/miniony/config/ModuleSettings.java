package pl.skyblock.miniony.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

/**
 * Wspólne ustawienia modułu.
 *
 * @param allowedTypes typy minionów, w których moduł można zainstalować (puste = wszystkie)
 */
public record ModuleSettings(
        ModuleKind kind,
        boolean enabled,
        String name,
        Material icon,
        List<String> description,
        UpgradeCost cost,
        Set<String> allowedTypes
) {

    public boolean allows(String typeId) {
        return allowedTypes.isEmpty() || allowedTypes.contains(typeId);
    }
}

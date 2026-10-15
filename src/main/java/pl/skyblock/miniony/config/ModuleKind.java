package pl.skyblock.miniony.config;

/** Rodzaje modułów. Identyfikator to klucz sekcji w "moduly" i wartość zapisywana w bazie. */
public enum ModuleKind {
    AUTO_SMELT("auto-przetapianie"),
    AUTO_SELL("auto-sprzedaz"),
    COMPACTOR("kompaktor"),
    FUEL("paliwo"),
    RANGE("rozszerzenie-zasiegu"),
    CHEST_LINK("lacze-skrzyni");

    private final String id;

    ModuleKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ModuleKind byId(String id) {
        for (ModuleKind kind : values()) {
            if (kind.id.equalsIgnoreCase(id)) {
                return kind;
            }
        }
        return null;
    }
}

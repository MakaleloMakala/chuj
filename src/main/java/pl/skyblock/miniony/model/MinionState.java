package pl.skyblock.miniony.model;

/** Stan miniona w bazie. */
public enum MinionState {
    /** Minion stoi w świecie. */
    PLACED,
    /** Minion istnieje jako przedmiot (w ekwipunku, skrzyni, na ziemi albo czeka na zwrot). */
    ITEM
}

package pitiupi.bingo.net;

/** Padrão de vitória escolhido pelo dono da sala. */
public enum Pattern {
    FULL_CARD("cartela cheia"),
    LINE("uma linha, coluna ou diagonal");

    private final String label;

    Pattern(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

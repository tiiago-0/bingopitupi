package pitiupi.bingo.net;

import java.util.List;

/** ANÚNCIO: dono da sala → sala (UDP multicast, a cada 2 s enquanto a sala de espera está aberta). */
public class AnnounceMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private String title;
    private Pattern pattern;
    private List<String> playerNames;
    private boolean open;

    public AnnounceMessage() {
    }

    public AnnounceMessage(String title, Pattern pattern, List<String> playerNames, boolean open) {
        this.title = title;
        this.pattern = pattern;
        this.playerNames = List.copyOf(playerNames);
        this.open = open;
    }

    public String getTitle() { return title; }
    public Pattern getPattern() { return pattern; }
    public List<String> getPlayerNames() { return playerNames; }
    /** {@code false}: a partida começou ou foi cancelada; sai da lista de partidas abertas. */
    public boolean isOpen() { return open; }

    @Override public String type() { return "ANÚNCIO"; }
}

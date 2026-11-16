package pitiupi.bingo.net;

import java.util.List;

/** INÍCIO: dono da sala → sala (UDP multicast, 3 cópias). Padrão de vitória e lista oficial de jogadores. */
public class StartMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private Pattern pattern;
    private List<String> playerIds;
    private List<String> playerNames;

    public StartMessage() {
    }

    public StartMessage(Pattern pattern, List<String> playerIds, List<String> playerNames) {
        this.pattern = pattern;
        this.playerIds = List.copyOf(playerIds);
        this.playerNames = List.copyOf(playerNames);
    }

    public Pattern getPattern() { return pattern; }
    public List<String> getPlayerIds() { return playerIds; }
    public List<String> getPlayerNames() { return playerNames; }

    @Override public String type() { return "INÍCIO"; }
}

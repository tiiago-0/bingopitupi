package pitiupi.bingo.net;

import java.util.List;

/** FIM: dono da sala → sala (UDP multicast, 3 cópias). Vencedores ou cancelamento. */
public class EndMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private boolean cancelled;
    private List<String> winnerIds;
    private List<String> winnerNames;
    private int drawCount;

    public EndMessage() {
    }

    public EndMessage(boolean cancelled, List<String> winnerIds, List<String> winnerNames, int drawCount) {
        this.cancelled = cancelled;
        this.winnerIds = List.copyOf(winnerIds);
        this.winnerNames = List.copyOf(winnerNames);
        this.drawCount = drawCount;
    }

    public boolean isCancelled() { return cancelled; }
    public List<String> getWinnerIds() { return winnerIds; }
    public List<String> getWinnerNames() { return winnerNames; }
    public int getDrawCount() { return drawCount; }

    @Override public String type() { return "FIM"; }
}

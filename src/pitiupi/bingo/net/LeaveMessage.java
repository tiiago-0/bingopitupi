package pitiupi.bingo.net;

/** SAÍDA: jogador → dono da sala (TCP). O jogador saiu por vontade própria. */
public class LeaveMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    public LeaveMessage() {
    }

    @Override public String type() { return "SAÍDA"; }
    @Override public boolean isForHost() { return true; }
}

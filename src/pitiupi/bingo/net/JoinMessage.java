package pitiupi.bingo.net;

/** ENTRADA: jogador → dono da sala (TCP). Pedido para entrar e receber uma cartela. */
public class JoinMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    public JoinMessage() {
    }

    @Override public String type() { return "ENTRADA"; }
    @Override public boolean isForHost() { return true; }
}

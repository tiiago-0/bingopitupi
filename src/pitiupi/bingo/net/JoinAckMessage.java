package pitiupi.bingo.net;

/**
 * ACEITE: dono da sala → jogador (TCP). Aceita a entrada e entrega a cartela
 * sorteada pelo dono, ou recusa com o motivo.
 */
public class JoinAckMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private boolean accepted;
    private String reason;
    private int[] card;
    private Pattern pattern;

    public JoinAckMessage() {
    }

    public JoinAckMessage(boolean accepted, String reason, int[] card, Pattern pattern) {
        this.accepted = accepted;
        this.reason = reason;
        this.card = card == null ? null : card.clone();
        this.pattern = pattern;
    }

    public boolean isAccepted() { return accepted; }
    public String getReason() { return reason; }
    /** 25 números, linha por linha; o 0 no centro é a casa livre. */
    public int[] getCard() { return card == null ? null : card.clone(); }
    public Pattern getPattern() { return pattern; }

    @Override public String type() { return "ACEITE"; }
}

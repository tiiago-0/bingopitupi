package pitiupi.bingo.net;

/**
 * BINGO: jogador → dono da sala (TCP). O jogador grita BINGO e envia a
 * cartela com as casas que marcou, para o dono conferir.
 */
public class ClaimMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private int drawCountSeen;
    private int[] card;
    private boolean[] marks;

    public ClaimMessage() {
    }

    public ClaimMessage(int drawCountSeen, int[] card, boolean[] marks) {
        this.drawCountSeen = drawCountSeen;
        this.card = card.clone();
        this.marks = marks.clone();
    }

    /** Quantos números o jogador tinha visto ao gritar. */
    public int getDrawCountSeen() { return drawCountSeen; }
    public int[] getCard() { return card.clone(); }
    /** 25 posições, linha por linha; {@code true} = casa marcada. */
    public boolean[] getMarks() { return marks.clone(); }

    @Override public String type() { return "BINGO"; }
    @Override public boolean isForHost() { return true; }
}

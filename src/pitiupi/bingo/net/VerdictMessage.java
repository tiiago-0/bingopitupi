package pitiupi.bingo.net;

/**
 * VEREDITO: dono da sala → sala (UDP multicast, 3 cópias) e → quem gritou (TCP).
 * Diz se o BINGO vale e mostra a cartela conferida a todos.
 */
public class VerdictMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private String claimantId;
    private String claimantName;
    private boolean valid;
    private String reason;
    private int[] card;
    private boolean[] marks;
    private int drawCount;

    public VerdictMessage() {
    }

    public VerdictMessage(String claimantId, String claimantName, boolean valid, String reason,
                          int[] card, boolean[] marks, int drawCount) {
        this.claimantId = claimantId;
        this.claimantName = claimantName;
        this.valid = valid;
        this.reason = reason;
        this.card = card.clone();
        this.marks = marks.clone();
        this.drawCount = drawCount;
    }

    public String getClaimantId() { return claimantId; }
    public String getClaimantName() { return claimantName; }
    public boolean isValid() { return valid; }
    public String getReason() { return reason; }
    public int[] getCard() { return card.clone(); }
    public boolean[] getMarks() { return marks.clone(); }
    /** Quantos números tinham saído quando a cartela foi conferida. */
    public int getDrawCount() { return drawCount; }

    @Override public String type() { return "VEREDITO"; }
}

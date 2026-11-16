package pitiupi.bingo.net;

/** CONFERÊNCIA: dono da sala → sala (UDP multicast, 3 cópias). Alguém gritou BINGO; o sorteio está pausado. */
public class CheckingMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private String claimantId;
    private String claimantName;

    public CheckingMessage() {
    }

    public CheckingMessage(String claimantId, String claimantName) {
        this.claimantId = claimantId;
        this.claimantName = claimantName;
    }

    public String getClaimantId() { return claimantId; }
    public String getClaimantName() { return claimantName; }

    @Override public String type() { return "CONFERÊNCIA"; }
}

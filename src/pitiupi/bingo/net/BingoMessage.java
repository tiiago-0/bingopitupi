package pitiupi.bingo.net;

import pitiupi.models.Messages.Message;

/**
 * Cabeçalho comum a todas as mensagens do protocolo BINGO/1.
 *
 * <p>Leva a versão do protocolo, o id da partida, o id da instância que
 * enviou (o mesmo {@code instanceId} do sinal de presença do núcleo), o nome
 * de quem enviou e um número de sequência. O par (senderId, seq) identifica o
 * pacote e permite descartar as cópias repetidas enviadas por multicast.</p>
 */
public abstract class BingoMessage extends Message {

    private static final long serialVersionUID = 1L;

    public static final int PROTOCOL_VERSION = 1;

    private int version = PROTOCOL_VERSION;
    private String sessionId;
    private String senderId;
    private String senderName;
    private long seq;
    private long sentAtMillis;

    protected BingoMessage() {
    }

    public void stamp(String sessionId, String senderId, String senderName, long seq) {
        this.sessionId = sessionId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.seq = seq;
        this.sentAtMillis = System.currentTimeMillis();
    }

    public int getVersion() { return version; }
    public String getSessionId() { return sessionId; }
    public String getSenderId() { return senderId; }
    public String getSenderName() { return senderName; }
    public long getSeq() { return seq; }
    public long getSentAtMillis() { return sentAtMillis; }

    /** Nome da mensagem no log da interface. */
    public abstract String type();

    /** {@code true} para as mensagens que só o dono da sala trata. */
    public boolean isForHost() {
        return false;
    }
}

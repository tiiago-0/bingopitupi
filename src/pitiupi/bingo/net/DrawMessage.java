package pitiupi.bingo.net;

/**
 * NÚMERO: dono da sala → sala (UDP multicast, 3 cópias).
 *
 * <p>Além do número novo, leva a lista de <b>todos</b> os números já
 * sorteados. Assim, quem perdeu um pacote se recupera sozinho com o próximo:
 * cada mensagem traz o estado completo, nunca só a diferença.</p>
 */
public class DrawMessage extends BingoMessage {

    private static final long serialVersionUID = 1L;

    private int drawCount;
    private int number;
    private int[] drawn;

    public DrawMessage() {
    }

    public DrawMessage(int drawCount, int number, int[] drawn) {
        this.drawCount = drawCount;
        this.number = number;
        this.drawn = drawn.clone();
    }

    /** Quantos números já saíram, contando este (1 a 75). */
    public int getDrawCount() { return drawCount; }
    public int getNumber() { return number; }
    /** Todos os números sorteados, na ordem em que saíram. */
    public int[] getDrawn() { return drawn.clone(); }

    @Override public String type() { return "NÚMERO"; }
}

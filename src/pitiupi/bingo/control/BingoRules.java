package pitiupi.bingo.control;

import pitiupi.bingo.net.Pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Regras do bingo de 75 bolas, sem rede e sem interface, para poderem ser
 * testadas sozinhas.
 *
 * <p>A cartela tem 5 colunas, B, I, N, G e O. A coluna B tem números de 1 a 15,
 * a I de 16 a 30, a N de 31 a 45, a G de 46 a 60 e a O de 61 a 75. A casa do
 * centro é livre. A cartela é guardada como 25 números, linha por linha
 * (posição = linha × 5 + coluna), com 0 na casa livre.</p>
 */
public final class BingoRules {

    public static final int BALLS = 75;
    public static final int SIZE = 5;
    public static final int CELLS = SIZE * SIZE;
    public static final int FREE = 12;
    public static final String LETTERS = "BINGO";

    private BingoRules() {
    }

    /** Letra da coluna de um número: 7 → B, 40 → N. */
    public static char letterOf(int number) {
        return LETTERS.charAt((number - 1) / 15);
    }

    /** Número com a letra, como é cantado: "B 7", "N 40". */
    public static String call(int number) {
        return letterOf(number) + " " + number;
    }

    /** Sorteia uma cartela: 5 números diferentes por coluna, dentro da faixa da coluna, e o centro livre. */
    public static int[] newCard(Random random) {
        int[] card = new int[CELLS];
        for (int col = 0; col < SIZE; col++) {
            List<Integer> pool = new ArrayList<>();
            for (int n = col * 15 + 1; n <= col * 15 + 15; n++) {
                pool.add(n);
            }
            Collections.shuffle(pool, random);
            for (int row = 0; row < SIZE; row++) {
                card[row * SIZE + col] = pool.get(row);
            }
        }
        card[FREE] = 0;
        return card;
    }

    /** Sorteia o próximo número entre os que ainda não saíram, ou −1 se já saíram todos. */
    public static int drawNumber(Set<Integer> drawn, Random random) {
        List<Integer> left = new ArrayList<>();
        for (int n = 1; n <= BALLS; n++) {
            if (!drawn.contains(n)) {
                left.add(n);
            }
        }
        return left.isEmpty() ? -1 : left.get(random.nextInt(left.size()));
    }

    /** As linhas que valem para o padrão: a cartela inteira ou cada linha, coluna e diagonal. */
    public static List<int[]> lines(Pattern pattern) {
        List<int[]> lines = new ArrayList<>();
        if (pattern == Pattern.FULL_CARD) {
            int[] all = new int[CELLS];
            for (int i = 0; i < CELLS; i++) {
                all[i] = i;
            }
            lines.add(all);
            return lines;
        }
        for (int r = 0; r < SIZE; r++) {
            int[] row = new int[SIZE];
            int[] col = new int[SIZE];
            for (int c = 0; c < SIZE; c++) {
                row[c] = r * SIZE + c;
                col[c] = c * SIZE + r;
            }
            lines.add(row);
            lines.add(col);
        }
        int[] d1 = new int[SIZE];
        int[] d2 = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            d1[i] = i * SIZE + i;
            d2[i] = i * SIZE + (SIZE - 1 - i);
        }
        lines.add(d1);
        lines.add(d2);
        return lines;
    }

    /** Resultado da conferência de uma cartela. */
    public record Check(boolean valid, String reason) {
    }

    /**
     * Confere um BINGO, como o dono da sala faz na mesa:
     * <ol>
     *   <li>a cartela enviada tem de ser a mesma que o dono entregou;</li>
     *   <li>toda casa marcada (fora a livre) tem de ter um número já sorteado;</li>
     *   <li>alguma linha do padrão tem de estar toda marcada.</li>
     * </ol>
     */
    public static Check check(int[] registered, int[] claimedCard, boolean[] marks, Set<Integer> drawn, Pattern pattern) {
        if (registered == null) {
            return new Check(false, "o dono da sala não entregou cartela para este jogador");
        }
        if (claimedCard == null || marks == null || claimedCard.length != CELLS || marks.length != CELLS
                || !java.util.Arrays.equals(registered, claimedCard)) {
            return new Check(false, "a cartela enviada é diferente da que o dono da sala entregou");
        }
        List<Integer> wrong = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            if (i != FREE && marks[i] && !drawn.contains(registered[i])) {
                wrong.add(registered[i]);
            }
        }
        if (!wrong.isEmpty()) {
            Collections.sort(wrong);
            String list;
            if (wrong.size() == 1) {
                list = "o número " + call(wrong.get(0)) + ", que não foi sorteado";
            } else if (wrong.size() <= 5) {
                list = "os números " + joinCalls(wrong) + ", que não foram sorteados";
            } else {
                list = wrong.size() + " números que não foram sorteados (" + joinCalls(wrong.subList(0, 5)).replace(" e ", ", ")
                        + "…)";
            }
            return new Check(false, "marcou " + list);
        }
        int bestMissing = Integer.MAX_VALUE;
        for (int[] line : lines(pattern)) {
            int missing = 0;
            for (int i : line) {
                if (i != FREE && !marks[i]) {
                    missing++;
                }
            }
            if (missing == 0) {
                return new Check(true, pattern == Pattern.FULL_CARD ? "cartela cheia conferida"
                        : "linha completa conferida");
            }
            bestMissing = Math.min(bestMissing, missing);
        }
        String what = pattern == Pattern.FULL_CARD ? "a cartela" : "nenhuma linha";
        return new Check(false, pattern == Pattern.FULL_CARD
                ? what + " ainda não está cheia: falta" + (bestMissing == 1 ? " 1 número" : "m " + bestMissing + " números")
                : what + " está completa: a mais adiantada ainda tem " + bestMissing
                + (bestMissing == 1 ? " casa sem marcar" : " casas sem marcar"));
    }

    private static String joinCalls(List<Integer> numbers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) {
                sb.append(i == numbers.size() - 1 ? " e " : ", ");
            }
            sb.append(call(numbers.get(i)));
        }
        return sb.toString();
    }
}

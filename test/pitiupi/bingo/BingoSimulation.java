package pitiupi.bingo;

import pitiupi.bingo.control.BingoHost;
import pitiupi.bingo.control.BingoPlayer;
import pitiupi.bingo.control.BingoRules;
import pitiupi.bingo.control.BingoTransport;
import pitiupi.bingo.control.ProtocolLog;
import pitiupi.bingo.net.BingoMessage;
import pitiupi.bingo.net.Pattern;
import pitiupi.bingo.net.VerdictMessage;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Partida simulada sem interface e sem sockets: o dono da sala e três
 * jogadores trocam as mensagens reais do BINGO/1, serializadas em bytes, por
 * uma rede em memória com atraso e 30% de perda nos datagramas UDP.
 *
 * <ul>
 *   <li>Ana marca só os números sorteados e grita BINGO quando completa a cartela;</li>
 *   <li>Beto marca a cartela inteira e grita BINGO logo no 5º número (bingo falso);</li>
 *   <li>Caio marca os números, mas nunca grita.</li>
 * </ul>
 */
public final class BingoSimulation {

    static final double UDP_LOSS = 0.30;
    static final Random RNG = new Random(5);
    static final ScheduledExecutorService WIRE = Executors.newScheduledThreadPool(4);
    static final Map<String, Node> NODES = new ConcurrentHashMap<>();
    static final AtomicInteger DROPPED = new AtomicInteger();

    static final class Node implements BingoTransport {
        final String id;
        final String name;
        final InetAddress addr;
        volatile BingoHost host;
        volatile BingoPlayer player;

        Node(String id, String name, int last) throws Exception {
            this.id = id;
            this.name = name;
            this.addr = InetAddress.getByName("10.0.0." + last);
        }

        void fromWire(byte[] bytes) {
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                BingoMessage m = (BingoMessage) in.readObject();
                if (m.getSenderId().equals(id)) {
                    return;
                }
                if (m.isForHost()) {
                    if (host != null) host.handle(m);
                } else if (player != null) {
                    player.handle(m);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        public String selfId() { return id; }
        public String selfName() { return name; }

        public boolean multicast(BingoMessage m) {
            for (Node n : NODES.values()) {
                if (n != this) udp(m, n);
            }
            return true;
        }

        public boolean unicastUdp(BingoMessage m, InetAddress a) {
            NODES.values().stream().filter(n -> n.addr.equals(a)).forEach(n -> udp(m, n));
            return true;
        }

        public boolean tcp(BingoMessage m, InetAddress a) {
            byte[] b = bytes(m);
            NODES.values().stream().filter(n -> n.addr.equals(a))
                    .forEach(n -> WIRE.schedule(() -> n.fromWire(b), 25, TimeUnit.MILLISECONDS));
            return true;
        }

        private void udp(BingoMessage m, Node n) {
            if (RNG.nextDouble() < UDP_LOSS) {
                DROPPED.incrementAndGet();
                return;
            }
            byte[] b = bytes(m);
            WIRE.schedule(() -> n.fromWire(b), 5 + RNG.nextInt(40), TimeUnit.MILLISECONDS);
        }

        public Optional<InetAddress> addressOf(String other) {
            Node n = NODES.get(other);
            return n == null || n == this ? Optional.empty() : Optional.of(n.addr);
        }
    }

    static byte[] bytes(BingoMessage m) {
        try {
            return m.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FALHOU: " + what);
        }
        System.out.println("  ok  " + what);
    }

    static boolean[] honestMarks(int[] card, int[] drawn) {
        Set<Integer> d = new HashSet<>();
        for (int n : drawn) d.add(n);
        boolean[] marks = new boolean[BingoRules.CELLS];
        for (int i = 0; i < BingoRules.CELLS; i++) {
            marks[i] = i == BingoRules.FREE || d.contains(card[i]);
        }
        return marks;
    }

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    static void run() throws Exception {
        System.out.println("== Regras");
        int[] card = BingoRules.newCard(new Random(1));
        boolean columnsOk = true;
        for (int i = 0; i < BingoRules.CELLS; i++) {
            int col = i % 5;
            if (i == BingoRules.FREE) {
                columnsOk &= card[i] == 0;
            } else {
                columnsOk &= card[i] >= col * 15 + 1 && card[i] <= col * 15 + 15;
            }
        }
        check(columnsOk, "cartela: cada coluna B, I, N, G, O na sua faixa e o centro livre");
        check(BingoRules.call(7).equals("B 7") && BingoRules.call(40).equals("N 40") && BingoRules.call(75).equals("O 75"),
                "números cantados com a letra da coluna");
        Set<Integer> all = new HashSet<>();
        for (int n = 1; n <= 75; n++) all.add(n);
        boolean[] full = new boolean[25];
        java.util.Arrays.fill(full, true);
        check(BingoRules.check(card, card, full, all, Pattern.FULL_CARD).valid(), "cartela cheia com tudo sorteado é válida");
        Set<Integer> few = new HashSet<>(List.of(card[0], card[1]));
        BingoRules.Check fake = BingoRules.check(card, card, full, few, Pattern.FULL_CARD);
        check(!fake.valid() && fake.reason().contains("não foram sorteados"), "marcar número não sorteado é bingo falso: " + fake.reason());
        int[] other = card.clone();
        other[0] = other[0] == 1 ? 2 : 1;
        check(!BingoRules.check(card, other, full, all, Pattern.FULL_CARD).valid(), "cartela diferente da entregue é recusada");
        Set<Integer> row = new HashSet<>();
        boolean[] rowMarks = new boolean[25];
        for (int c = 0; c < 5; c++) {
            row.add(card[2 * 5 + c]);
            rowMarks[2 * 5 + c] = true;
        }
        check(BingoRules.check(card, card, rowMarks, row, Pattern.LINE).valid(), "padrão linha: linha do meio (com a casa livre) vale");
        check(!BingoRules.check(card, card, rowMarks, row, Pattern.FULL_CARD).valid(), "a mesma linha não vale no padrão cartela cheia");

        System.out.println("== Partida simulada (cartela cheia, perda UDP " + (int) (UDP_LOSS * 100) + "%)");
        Node h = new Node("dono-1", "Prof", 1);
        Node a = new Node("ana-1", "Ana", 2);
        Node b = new Node("beto-1", "Beto", 3);
        Node c = new Node("caio-1", "Caio", 4);
        for (Node n : List.of(h, a, b, c)) {
            NODES.put(n.id, n);
        }

        CountDownLatch joined = new CountDownLatch(3);
        CountDownLatch ended = new CountDownLatch(3);
        List<VerdictMessage> verdictsSeenByCaio = new CopyOnWriteArrayList<>();
        List<VerdictMessage> betoOwnVerdict = new CopyOnWriteArrayList<>();
        Map<String, List<String>> winnersSeen = new ConcurrentHashMap<>();
        Map<String, Integer> missedTotal = new ConcurrentHashMap<>();
        AtomicInteger betoClaimDraw = new AtomicInteger(-1);
        AtomicInteger hostEndDraw = new AtomicInteger(-1);
        List<String> hostWinners = new CopyOnWriteArrayList<>();

        for (Node n : List.of(a, b, c)) {
            n.player = new BingoPlayer(n, ProtocolLog.NONE, new BingoPlayer.View() {
                @Override
                public void lobbiesChanged(List<BingoPlayer.Lobby> lobbies) {
                    if (!lobbies.isEmpty() && n.player.state() == BingoPlayer.State.IDLE) {
                        n.player.join(lobbies.get(0).sessionId());
                    }
                }

                @Override
                public void joinResult(boolean accepted, String reason, BingoPlayer.Lobby lobby, int[] cardOf, Pattern p) {
                    if (accepted) joined.countDown();
                }

                @Override
                public void drawn(int number, int[] drawn, int missedBefore) {
                    missedTotal.merge(n.name, missedBefore, Integer::sum);
                    int[] myCard = n.player.card();
                    if (n == a) {
                        boolean[] marks = honestMarks(myCard, drawn);
                        Set<Integer> d = new HashSet<>();
                        for (int x : drawn) d.add(x);
                        if (BingoRules.check(myCard, myCard, marks, d, Pattern.FULL_CARD).valid()) {
                            n.player.claim(marks);
                        }
                    } else if (n == b && drawn.length >= 5 && betoClaimDraw.get() < 0) {
                        betoClaimDraw.set(drawn.length);
                        boolean[] everything = new boolean[25];
                        java.util.Arrays.fill(everything, true);
                        n.player.claim(everything);
                    }
                }

                @Override
                public void verdict(VerdictMessage v, boolean mine) {
                    if (n == c) verdictsSeenByCaio.add(v);
                    if (n == b && mine) betoOwnVerdict.add(v);
                }

                @Override
                public void finished(List<String> winners, boolean iWon, boolean cancelled, String reason, int drawCount) {
                    winnersSeen.put(n.name, winners);
                    ended.countDown();
                }
            });
        }

        h.host = new BingoHost(h, ProtocolLog.NONE, new BingoHost.View() {
            @Override
            public void finished(List<String> winners, boolean cancelled, int drawCount) {
                hostWinners.addAll(winners);
                hostEndDraw.set(drawCount);
            }
        }, "Teste", Pattern.FULL_CARD);
        h.host.open();
        check(joined.await(15, TimeUnit.SECONDS), "Ana, Beto e Caio descobriram a partida (ANÚNCIO) e receberam a cartela (ACEITE)");
        check(h.host.start(), "dono da sala começou a partida");
        Thread.sleep(500);
        // O "dono" aperta Sortear a cada 120 ms; enquanto confere um BINGO, o sorteio fica pausado.
        java.util.concurrent.ScheduledFuture<?> clicker = WIRE.scheduleAtFixedRate(h.host::drawNow, 0, 120, TimeUnit.MILLISECONDS);
        check(ended.await(90, TimeUnit.SECONDS), "os 3 jogadores receberam o FIM");
        clicker.cancel(false);
        Thread.sleep(500);

        check(hostWinners.equals(List.of("Ana")), "vencedora conferida pelo dono da sala: Ana");
        for (String who : List.of("Ana", "Beto", "Caio")) {
            check(winnersSeen.get(who).equals(List.of("Ana")), who + " recebeu o FIM com a vencedora Ana");
        }
        VerdictMessage betoVerdict = betoOwnVerdict.isEmpty() ? null : betoOwnVerdict.get(0);
        VerdictMessage anaVerdict = verdictsSeenByCaio.stream().filter(v -> v.getClaimantName().equals("Ana")).findFirst().orElse(null);
        check(betoVerdict != null && !betoVerdict.isValid() && betoVerdict.getReason().contains("não foram sorteados"),
                "Beto recebeu o VEREDITO de bingo falso: " + (betoVerdict == null ? "?" : betoVerdict.getReason()));
        System.out.println("  (Caio viu " + verdictsSeenByCaio.size() + " VEREDITO(s) pelo multicast)");
        check(anaVerdict == null || anaVerdict.isValid(), "VEREDITO da Ana: válido");
        check(hostEndDraw.get() > betoClaimDraw.get() + 10, "o jogo continuou depois do bingo falso (terminou com "
                + hostEndDraw.get() + " números)");
        System.out.println("  (NÚMEROS cujas 3 cópias se perderam e foram recuperados pela lista completa do NÚMERO seguinte: "
                + missedTotal + ")");
        System.out.println("  (datagramas UDP descartados pela rede simulada: " + DROPPED.get() + ")");
        System.out.println("TUDO OK");
        System.exit(0);
    }
}

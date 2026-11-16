package pitiupi.bingo.control;

import pitiupi.bingo.net.AnnounceMessage;
import pitiupi.bingo.net.BingoMessage;
import pitiupi.bingo.net.CheckingMessage;
import pitiupi.bingo.net.ClaimMessage;
import pitiupi.bingo.net.DrawMessage;
import pitiupi.bingo.net.EndMessage;
import pitiupi.bingo.net.JoinAckMessage;
import pitiupi.bingo.net.JoinMessage;
import pitiupi.bingo.net.LeaveMessage;
import pitiupi.bingo.net.Pattern;
import pitiupi.bingo.net.StartMessage;
import pitiupi.bingo.net.VerdictMessage;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lado do jogador: descobre partidas pelos ANÚNCIOs, entra e recebe a
 * cartela, acompanha os números sorteados e grita BINGO. O BINGO é reenviado
 * até chegar o VEREDITO do dono da sala.
 */
public final class BingoPlayer {

    public enum State { IDLE, JOINING, LOBBY, PLAYING, FINISHED }

    public static final long LOBBY_EXPIRY = 7000;
    public static final long JOIN_TIMEOUT = 6000;
    public static final long VERDICT_TIMEOUT = 3000;
    public static final int MAX_TRIES = 3;
    public static final long HOST_CHECK_EVERY = 2000;

    /** Partida visível na lista de partidas abertas. */
    public record Lobby(String sessionId, String hostId, String hostName, String title, Pattern pattern,
                        List<String> playerNames, long lastSeen) {
    }

    /** Callbacks para a interface. Chamados fora da thread do Swing. */
    public interface View {
        default void lobbiesChanged(List<Lobby> lobbies) { }
        default void joinResult(boolean accepted, String reason, Lobby lobby, int[] card, Pattern pattern) { }
        default void lobbyPlayers(List<String> names) { }
        default void started(StartMessage start) { }
        default void drawn(int number, int[] drawn, int missedBefore) { }
        default void checking(CheckingMessage checking) { }
        default void verdict(VerdictMessage verdict, boolean mine) { }
        default void finished(List<String> winners, boolean iWon, boolean cancelled, String reason, int drawCount) { }
        default void status(String text, boolean ok) { }
    }

    private final BingoTransport net;
    private final ProtocolLog log;
    private final View view;
    private final AtomicLong seq = new AtomicLong();
    private final Dedup dedup = new Dedup();
    private final Map<String, Lobby> lobbies = new LinkedHashMap<>();

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "bingo-player-timer"));
    private final ExecutorService sender = Executors.newCachedThreadPool(r -> daemon(r, "bingo-player-send"));

    private State state = State.IDLE;
    private Lobby current;
    private int[] card;
    private int drawCount;
    private ScheduledFuture<?> joinTimeout;
    private ScheduledFuture<?> claimRetry;
    private int claimAt = -1;

    public BingoPlayer(BingoTransport net, ProtocolLog log, View view) {
        this.net = net;
        this.log = log == null ? ProtocolLog.NONE : log;
        this.view = view == null ? new View() { } : view;
        timers.scheduleAtFixedRate(this::housekeeping, HOST_CHECK_EVERY, HOST_CHECK_EVERY, TimeUnit.MILLISECONDS);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int[] card() {
        return card == null ? null : card.clone();
    }

    public synchronized int drawCount() {
        return drawCount;
    }

    // ================================================================== ações da interface

    public synchronized void join(String sessionId) {
        Lobby lobby = lobbies.get(sessionId);
        if (lobby == null || (state != State.IDLE && state != State.FINISHED)) {
            return;
        }
        if (net.addressOf(lobby.hostId()).isEmpty()) {
            view.joinResult(false, "O dono da sala não aparece na lista de usuários conectados.", lobby, null, null);
            return;
        }
        reset();
        current = lobby;
        state = State.JOINING;
        send(new JoinMessage(), "pedido de entrada");
        joinTimeout = timers.schedule(() -> {
            synchronized (this) {
                if (state == State.JOINING) {
                    log.note("Nenhum ACEITE em " + JOIN_TIMEOUT / 1000 + " s: pedido de entrada abandonado.");
                    view.joinResult(false, "O dono da sala não respondeu. Verifique se o firewall libera TCP na porta.",
                            current, null, null);
                    reset();
                }
            }
        }, JOIN_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    public synchronized void leave() {
        if (current != null && state != State.IDLE && state != State.FINISHED) {
            send(new LeaveMessage(), "saindo da partida");
        }
        reset();
    }

    /**
     * Botão BINGO. Envia a cartela e as marcações por TCP e espera o VEREDITO;
     * sem resposta em 3 s, reenvia (até 3 tentativas).
     *
     * @return {@code false} se não há partida em andamento ou se já gritou com este mesmo número
     */
    public synchronized boolean claim(boolean[] marks) {
        if (state != State.PLAYING || card == null) {
            return false;
        }
        if (claimAt == drawCount) {
            view.status("Você já gritou BINGO com este número. Espere o próximo número para tentar de novo.", false);
            return false;
        }
        claimAt = drawCount;
        attemptClaim(drawCount, marks.clone(), 1);
        view.status("BINGO enviado! Aguardando a conferência do dono da sala…", true);
        return true;
    }

    private void attemptClaim(int at, boolean[] marks, int n) {
        send(new ClaimMessage(at, card, marks), n == 1 ? "cartela com " + countMarks(marks) + " casas marcadas"
                : "tentativa " + n);
        claimRetry = timers.schedule(() -> {
            synchronized (this) {
                if (claimAt != at || state != State.PLAYING) {
                    return;
                }
                if (n >= MAX_TRIES) {
                    view.status("O dono da sala não respondeu ao seu BINGO.", false);
                    claimAt = -1;
                    return;
                }
                attemptClaim(at, marks, n + 1);
            }
        }, VERDICT_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        leave();
        timers.shutdownNow();
        sender.shutdown();
    }

    // ================================================================== recebimento

    public void handle(BingoMessage m) {
        if (!dedup.firstTime(m)) {
            return;
        }
        if (m instanceof AnnounceMessage a) {
            onAnnounce(a);
            return;
        }
        synchronized (this) {
            if (current == null || !current.sessionId().equals(m.getSessionId())) {
                return;
            }
        }
        if (m instanceof JoinAckMessage ack) {
            onJoinAck(ack);
        } else if (m instanceof StartMessage s) {
            onStart(s);
        } else if (m instanceof DrawMessage d) {
            onDraw(d);
        } else if (m instanceof CheckingMessage c) {
            onChecking(c);
        } else if (m instanceof VerdictMessage v) {
            onVerdict(v);
        } else if (m instanceof EndMessage e) {
            onEnd(e);
        }
    }

    private synchronized void onAnnounce(AnnounceMessage a) {
        if (current != null && current.sessionId().equals(a.getSessionId())) {
            view.lobbyPlayers(a.getPlayerNames());
        }
        boolean changed;
        if (a.isOpen()) {
            Lobby lobby = new Lobby(a.getSessionId(), a.getSenderId(), a.getSenderName(), a.getTitle(), a.getPattern(),
                    a.getPlayerNames(), System.currentTimeMillis());
            Lobby previous = lobbies.put(a.getSessionId(), lobby);
            changed = previous == null || !previous.playerNames().equals(lobby.playerNames());
            if (previous == null) {
                log.received(a.type(), a.getSenderName(), "UDP multicast · nova partida \"" + a.getTitle() + "\"");
            }
        } else {
            changed = lobbies.remove(a.getSessionId()) != null;
        }
        if (changed) {
            view.lobbiesChanged(listLobbies());
        }
    }

    private synchronized void onJoinAck(JoinAckMessage ack) {
        log.received(ack.type(), ack.getSenderName(), "TCP · " + (ack.isAccepted() ? "entrada aceita, cartela recebida"
                : "entrada recusada: " + ack.getReason()));
        if (state != State.JOINING) {
            return;
        }
        if (joinTimeout != null) {
            joinTimeout.cancel(false);
        }
        Lobby lobby = current;
        if (ack.isAccepted() && ack.getCard() != null) {
            card = ack.getCard();
            state = State.LOBBY;
            view.joinResult(true, null, lobby, card.clone(), ack.getPattern());
            view.lobbyPlayers(lobby.playerNames());
        } else {
            reset();
            view.joinResult(false, ack.getReason(), lobby, null, null);
        }
    }

    private synchronized void onStart(StartMessage s) {
        log.received(s.type(), s.getSenderName(), "UDP multicast · " + s.getPlayerIds().size() + " jogadores");
        if (!s.getPlayerIds().contains(net.selfId())) {
            if (state == State.JOINING || state == State.LOBBY) {
                view.finished(List.of(), false, true, "A partida começou sem você na lista.", 0);
                reset();
            }
            return;
        }
        if (state == State.LOBBY) {
            state = State.PLAYING;
            view.started(s);
        }
    }

    private synchronized void onDraw(DrawMessage d) {
        if (d.getDrawCount() <= drawCount) {
            return;
        }
        if (state == State.LOBBY) {
            log.note("O INÍCIO não chegou; entrando na partida pelo primeiro NÚMERO.");
            state = State.PLAYING;
        }
        if (state != State.PLAYING) {
            return;
        }
        int missed = d.getDrawCount() - drawCount - 1;
        if (missed > 0) {
            log.note(missed == 1 ? "1 número anterior não tinha chegado; recuperado pela lista completa."
                    : missed + " números anteriores não tinham chegado; recuperados pela lista completa.");
        }
        drawCount = d.getDrawCount();
        log.received(d.type(), d.getSenderName(), "UDP multicast · " + BingoRules.call(d.getNumber())
                + " (" + d.getDrawCount() + "º número)");
        view.drawn(d.getNumber(), d.getDrawn(), missed);
    }

    private synchronized void onChecking(CheckingMessage c) {
        log.received(c.type(), c.getSenderName(), "UDP multicast · BINGO de " + c.getClaimantName());
        view.checking(c);
    }

    private synchronized void onVerdict(VerdictMessage v) {
        boolean mine = net.selfId().equals(v.getClaimantId());
        log.received(v.type(), v.getSenderName(), v.getClaimantName() + ": " + (v.isValid() ? "BINGO válido" : "BINGO falso"));
        if (mine) {
            if (claimRetry != null) {
                claimRetry.cancel(false);
            }
            if (v.isValid()) {
                view.status("BINGO confirmado! Aguardando o fim da conferência…", true);
            } else {
                view.status("Bingo falso: " + v.getReason() + ". O jogo continua.", false);
            }
        }
        view.verdict(v, mine);
    }

    private synchronized void onEnd(EndMessage e) {
        log.received(e.type(), e.getSenderName(), "UDP multicast · " + (e.isCancelled() ? "partida cancelada"
                : "vencedor(es): " + String.join(", ", e.getWinnerNames())));
        boolean iWon = e.getWinnerIds().contains(net.selfId());
        view.finished(e.getWinnerNames(), iWon, e.isCancelled(),
                e.isCancelled() ? "O dono da sala encerrou a partida." : null, e.getDrawCount());
        reset();
        state = State.FINISHED;
    }

    // ================================================================== tarefas periódicas

    private synchronized void housekeeping() {
        long now = System.currentTimeMillis();
        if (lobbies.values().removeIf(l -> now - l.lastSeen() > LOBBY_EXPIRY)) {
            view.lobbiesChanged(listLobbies());
        }
        if (current != null && (state == State.LOBBY || state == State.PLAYING)
                && net.addressOf(current.hostId()).isEmpty()) {
            log.note("O dono da sala " + current.hostName() + " parou de enviar sinal de presença.");
            view.finished(List.of(), false, true,
                    "A conexão com o dono da sala foi perdida: ele parou de enviar sinal de presença.", drawCount);
            reset();
        }
    }

    // ================================================================== envio

    private void send(BingoMessage m, String detail) {
        m.stamp(current.sessionId(), net.selfId(), net.selfName(), seq.incrementAndGet());
        log.sent(m.type(), "TCP", current.hostName(), detail);
        String hostId = current.hostId();
        if (sender.isShutdown()) {
            return;
        }
        sender.execute(() -> {
            InetAddress addr = net.addressOf(hostId).orElse(null);
            if (addr == null || !net.tcp(m, addr)) {
                log.note("Falha ao enviar " + m.type() + " para o dono da sala.");
            }
        });
    }

    // ================================================================== apoio

    private void reset() {
        if (joinTimeout != null) {
            joinTimeout.cancel(false);
        }
        if (claimRetry != null) {
            claimRetry.cancel(false);
        }
        state = State.IDLE;
        current = null;
        card = null;
        drawCount = 0;
        claimAt = -1;
    }

    private static int countMarks(boolean[] marks) {
        int n = 0;
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] && i != BingoRules.FREE) {
                n++;
            }
        }
        return n;
    }

    private List<Lobby> listLobbies() {
        List<Lobby> list = new ArrayList<>(lobbies.values());
        list.sort(Comparator.comparing(Lobby::hostName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}

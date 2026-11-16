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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dono da sala: sorteia as cartelas e os números, recebe os pedidos de BINGO,
 * confere as cartelas e declara os vencedores. Não joga.
 *
 * <p>Toda alteração de estado acontece sob {@code synchronized (this)}. Os
 * envios vão para um executor separado, para que um {@code connect} TCP lento
 * não trave o sorteio.</p>
 */
public final class BingoHost {

    public enum State { LOBBY, RUNNING, CHECKING, FINISHED }

    public static final long ANNOUNCE_EVERY = 2000;
    public static final long PRESENCE_CHECK_EVERY = 2000;
    /** Depois do primeiro BINGO válido, espera este tempo por outros BINGOs do mesmo número (empate). */
    public static final long TIE_WINDOW = 2000;
    /** Pausa mínima depois de um BINGO falso antes de voltar a sortear. */
    public static final long RESUME_PAUSE = 2500;
    public static final int MULTICAST_COPIES = 3;
    public static final long COPY_SPACING = 120;
    public static final int MAX_PLAYERS = 40;

    /** Callbacks para a tela do dono da sala. Chamados fora da thread do Swing. */
    public interface View {
        default void playersChanged(List<PlayerRow> players) { }
        default void stateChanged(State state) { }
        default void drawn(int number, int[] drawn) { }
        default void verdict(VerdictMessage verdict) { }
        default void finished(List<String> winners, boolean cancelled, int drawCount) { }
    }

    /** Linha da tabela de jogadores do dono da sala. */
    public record PlayerRow(String id, String name, boolean online, int falseClaims) {
    }

    private static final class Player {
        final String id;
        final String name;
        final int[] card;
        InetAddress address;
        boolean online = true;
        int falseClaims;
        int lastClaimAt = -1;
        VerdictMessage lastVerdict;

        Player(String id, String name, int[] card, InetAddress address) {
            this.id = id;
            this.name = name;
            this.card = card;
            this.address = address;
        }
    }

    private final BingoTransport net;
    private final ProtocolLog log;
    private final View view;
    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
    private final String title;
    private final Pattern pattern;
    private final Random random = new Random();
    private final AtomicLong seq = new AtomicLong();
    private final Dedup dedup = new Dedup();

    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Set<Integer> drawn = new LinkedHashSet<>();
    private final Map<String, Boolean> windowClaims = new LinkedHashMap<>();

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "bingo-host-timer"));
    private final ExecutorService sender = Executors.newCachedThreadPool(r -> daemon(r, "bingo-host-send"));

    private State state = State.LOBBY;
    private boolean auto;
    private int autoSeconds = 5;
    private ScheduledFuture<?> autoTask;
    private ScheduledFuture<?> windowTask;

    public BingoHost(BingoTransport net, ProtocolLog log, View view, String title, Pattern pattern) {
        this.net = net;
        this.log = log == null ? ProtocolLog.NONE : log;
        this.view = view == null ? new View() { } : view;
        this.title = title;
        this.pattern = pattern;
    }

    // ================================================================== ciclo de vida e botões

    public void open() {
        log.note("Sala de espera aberta. Partida " + sessionId + ", padrão de vitória: " + pattern.label() + ".");
        timers.scheduleAtFixedRate(this::announce, 0, ANNOUNCE_EVERY, TimeUnit.MILLISECONDS);
        timers.scheduleAtFixedRate(this::checkPresence, PRESENCE_CHECK_EVERY, PRESENCE_CHECK_EVERY, TimeUnit.MILLISECONDS);
        view.stateChanged(state);
    }

    public String sessionId() {
        return sessionId;
    }

    public Pattern pattern() {
        return pattern;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int drawCount() {
        return drawn.size();
    }

    /** Botão "Começar partida": precisa de pelo menos 1 jogador. */
    public synchronized boolean start() {
        if (state != State.LOBBY || players.isEmpty()) {
            return false;
        }
        List<String> ids = new ArrayList<>(players.keySet());
        List<String> names = new ArrayList<>();
        for (Player p : players.values()) {
            names.add(p.name);
        }
        setState(State.RUNNING);
        multicastRedundant(new StartMessage(pattern, ids, names), players.size() + " jogadores");
        announceClosed();
        if (auto) {
            scheduleAuto(autoSeconds * 1000L);
        }
        return true;
    }

    /** Botão "Sortear número". */
    public void drawNow() {
        timers.execute(this::draw);
    }

    /** Liga ou desliga o sorteio automático a cada {@code seconds} segundos. */
    public synchronized void setAuto(boolean on, int seconds) {
        auto = on;
        autoSeconds = Math.max(1, seconds);
        cancelAuto();
        if (on && state == State.RUNNING) {
            scheduleAuto(autoSeconds * 1000L);
        }
    }

    public synchronized void cancel() {
        if (state == State.FINISHED) {
            shutdownLater();
            return;
        }
        cancelAuto();
        if (windowTask != null) {
            windowTask.cancel(false);
        }
        setState(State.FINISHED);
        broadcastEnd(List.of(), List.of(), true, "cancelada pelo dono da sala");
        announceClosed();
        view.finished(List.of(), true, drawn.size());
        shutdownLater();
    }

    private void shutdownLater() {
        timers.schedule(() -> {
            timers.shutdownNow();
            sender.shutdown();
        }, 1, TimeUnit.SECONDS);
    }

    // ================================================================== recebimento

    public void handle(BingoMessage m) {
        if (!sessionId.equals(m.getSessionId()) || !dedup.firstTime(m)) {
            return;
        }
        if (m instanceof JoinMessage j) {
            onJoin(j);
        } else if (m instanceof LeaveMessage l) {
            onLeave(l);
        } else if (m instanceof ClaimMessage c) {
            onClaim(c);
        }
    }

    private synchronized void onJoin(JoinMessage join) {
        log.received(join.type(), join.getSenderName(), "TCP");
        InetAddress addr = net.addressOf(join.getSenderId()).orElse(null);
        if (addr == null) {
            log.note("ENTRADA de " + join.getSenderName() + " sem sinal de presença recente: não sei o IP para responder.");
            return;
        }
        Player existing = players.get(join.getSenderId());
        if (existing != null) {
            existing.address = addr;
            existing.online = true;
            reply(existing.address, new JoinAckMessage(true, null, existing.card, pattern), existing.name, "a mesma cartela de antes");
            view.playersChanged(rows());
            return;
        }
        if (state != State.LOBBY) {
            reply(addr, new JoinAckMessage(false, "A partida já começou.", null, pattern), join.getSenderName(), "entrada recusada");
            return;
        }
        if (players.size() >= MAX_PLAYERS) {
            reply(addr, new JoinAckMessage(false, "A sala está cheia (" + MAX_PLAYERS + " jogadores).", null, pattern),
                    join.getSenderName(), "entrada recusada");
            return;
        }
        Player p = new Player(join.getSenderId(), join.getSenderName(), BingoRules.newCard(random), addr);
        players.put(p.id, p);
        reply(addr, new JoinAckMessage(true, null, p.card, pattern), p.name, "entrada aceita, cartela entregue");
        view.playersChanged(rows());
    }

    private synchronized void onLeave(LeaveMessage leave) {
        log.received(leave.type(), leave.getSenderName(), "TCP");
        if (state == State.LOBBY) {
            players.remove(leave.getSenderId());
        } else {
            Player p = players.get(leave.getSenderId());
            if (p != null) {
                p.online = false;
            }
        }
        view.playersChanged(rows());
    }

    private synchronized void onClaim(ClaimMessage c) {
        log.received(c.type(), c.getSenderName(), "TCP · tinha visto " + c.getDrawCountSeen() + " números");
        Player p = players.get(c.getSenderId());
        if (p == null) {
            log.note(c.getSenderName() + " gritou BINGO, mas não está nesta partida.");
            return;
        }
        if (state != State.RUNNING && state != State.CHECKING) {
            log.note("BINGO de " + p.name + " fora de hora (partida não está em andamento).");
            return;
        }
        if (p.lastClaimAt == drawn.size() && p.lastVerdict != null) {
            // Retransmissão do mesmo BINGO: o jogador não recebeu o VEREDITO. Reenvia só para ele, sem conferir de novo.
            reply(p.address, copyOf(p.lastVerdict), p.name, "VEREDITO reenviado (BINGO repetido)");
            return;
        }
        cancelAuto();
        if (state == State.RUNNING) {
            setState(State.CHECKING);
            multicastRedundant(new CheckingMessage(p.id, p.name), "BINGO de " + p.name + ", sorteio pausado");
        }
        BingoRules.Check check = BingoRules.check(p.card, c.getCard(), c.getMarks(), drawn, pattern);
        VerdictMessage verdict = new VerdictMessage(p.id, p.name, check.valid(), check.reason(), p.card,
                c.getMarks(), drawn.size());
        p.lastClaimAt = drawn.size();
        p.lastVerdict = verdict;
        if (!check.valid()) {
            p.falseClaims++;
        }
        windowClaims.put(p.id, check.valid());
        log.note("Conferência da cartela de " + p.name + ": " + (check.valid() ? "BINGO VÁLIDO" : "BINGO FALSO")
                + " (" + check.reason() + ").");
        multicastRedundant(verdict, p.name + ": " + (check.valid() ? "válido" : "falso"));
        reply(p.address, copyOf(verdict), p.name, "cópia do VEREDITO por TCP");
        view.verdict(verdict);
        view.playersChanged(rows());

        if (windowTask == null) {
            long wait = check.valid() ? TIE_WINDOW : RESUME_PAUSE;
            windowTask = timers.schedule(this::closeWindow, wait, TimeUnit.MILLISECONDS);
        } else if (check.valid() && !anyValidBefore(p.id)) {
            // Primeiro válido chegou depois de um falso: estende a janela para dar chance a empates.
            windowTask.cancel(false);
            windowTask = timers.schedule(this::closeWindow, TIE_WINDOW, TimeUnit.MILLISECONDS);
        }
    }

    private boolean anyValidBefore(String exceptId) {
        for (Map.Entry<String, Boolean> e : windowClaims.entrySet()) {
            if (!e.getKey().equals(exceptId) && e.getValue()) {
                return true;
            }
        }
        return false;
    }

    private synchronized void closeWindow() {
        windowTask = null;
        if (state != State.CHECKING) {
            windowClaims.clear();
            return;
        }
        List<String> winnerIds = new ArrayList<>();
        List<String> winnerNames = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : windowClaims.entrySet()) {
            if (e.getValue()) {
                winnerIds.add(e.getKey());
                winnerNames.add(players.get(e.getKey()).name);
            }
        }
        windowClaims.clear();
        if (!winnerIds.isEmpty()) {
            setState(State.FINISHED);
            if (winnerIds.size() > 1) {
                log.note("Empate: " + String.join(" e ", winnerNames) + " completaram com o mesmo número.");
            }
            broadcastEnd(winnerIds, winnerNames, false,
                    "vencedor" + (winnerIds.size() > 1 ? "es: " : ": ") + String.join(", ", winnerNames));
            view.finished(winnerNames, false, drawn.size());
            shutdownLater();
            return;
        }
        log.note("Nenhum BINGO válido. O sorteio continua.");
        setState(State.RUNNING);
        if (auto) {
            scheduleAuto(autoSeconds * 1000L);
        }
    }

    // ================================================================== sorteio

    private synchronized void draw() {
        if (state != State.RUNNING) {
            return;
        }
        int n = BingoRules.drawNumber(drawn, random);
        if (n < 0) {
            log.note("Todos os 75 números já saíram.");
            cancelAuto();
            return;
        }
        drawn.add(n);
        int[] all = drawn.stream().mapToInt(Integer::intValue).toArray();
        multicastRedundant(new DrawMessage(all.length, n, all), BingoRules.call(n) + " (" + all.length + "º número)");
        view.drawn(n, all);
        if (auto && drawn.size() < BingoRules.BALLS) {
            scheduleAuto(autoSeconds * 1000L);
        }
    }

    private void scheduleAuto(long delay) {
        cancelAuto();
        autoTask = timers.schedule(this::draw, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelAuto() {
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
    }

    // ================================================================== tarefas periódicas

    private synchronized void announce() {
        if (state != State.LOBBY) {
            return;
        }
        AnnounceMessage a = new AnnounceMessage(title, pattern, names(), true);
        stamp(a);
        sender.execute(() -> net.multicast(a));
    }

    private void announceClosed() {
        AnnounceMessage a = new AnnounceMessage(title, pattern, names(), false);
        stamp(a);
        log.sent(a.type(), "UDP multicast", "sala", "sala de espera fechada");
        sender.execute(() -> net.multicast(a));
    }

    private synchronized void checkPresence() {
        boolean changed = false;
        for (Player p : players.values()) {
            InetAddress addr = net.addressOf(p.id).orElse(null);
            boolean online = addr != null;
            if (online) {
                p.address = addr;
            }
            if (online != p.online) {
                p.online = online;
                changed = true;
                log.note(p.name + (online ? " voltou a enviar sinal de presença."
                        : " ficou sem enviar sinal de presença por cerca de 15 s e foi marcado como desconectado."));
            }
        }
        if (state == State.LOBBY) {
            changed |= players.values().removeIf(p -> !p.online);
        }
        if (changed) {
            view.playersChanged(rows());
        }
    }

    // ================================================================== envio

    private void multicastRedundant(BingoMessage m, String detail) {
        stamp(m);
        log.sent(m.type(), "UDP multicast ×" + MULTICAST_COPIES, "sala", detail);
        for (int i = 0; i < MULTICAST_COPIES; i++) {
            timers.schedule(() -> sender.execute(() -> net.multicast(m)), i * COPY_SPACING, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * O FIM vai por multicast (3 cópias) e também por TCP para cada jogador conectado:
     * quem perder as 3 cópias não pode ficar esperando um número que nunca mais vai sair.
     * O jogador processa só o primeiro FIM que chegar; os outros já não são da partida atual.
     */
    private void broadcastEnd(List<String> winnerIds, List<String> winnerNames, boolean cancelled, String detail) {
        multicastRedundant(new EndMessage(cancelled, winnerIds, winnerNames, drawn.size()), detail);
        for (Player p : players.values()) {
            if (p.online) {
                reply(p.address, new EndMessage(cancelled, winnerIds, winnerNames, drawn.size()), p.name, "cópia do FIM por TCP");
            }
        }
    }

    private void reply(InetAddress addr, BingoMessage m, String name, String detail) {
        stamp(m);
        log.sent(m.type(), "TCP", name, detail);
        if (sender.isShutdown()) {
            return;
        }
        sender.execute(() -> {
            if (!net.tcp(m, addr)) {
                log.note("Falha ao enviar " + m.type() + " por TCP para " + name + ".");
            }
        });
    }

    private void stamp(BingoMessage m) {
        m.stamp(sessionId, net.selfId(), net.selfName(), seq.incrementAndGet());
    }

    // ================================================================== apoio

    /** Cópia nova (com outro número de sequência) para envio por TCP, sem mexer na que está saindo por multicast. */
    private static VerdictMessage copyOf(VerdictMessage v) {
        return new VerdictMessage(v.getClaimantId(), v.getClaimantName(), v.isValid(), v.getReason(), v.getCard(),
                v.getMarks(), v.getDrawCount());
    }

    private void setState(State s) {
        state = s;
        view.stateChanged(s);
    }

    private List<String> names() {
        List<String> list = new ArrayList<>();
        for (Player p : players.values()) {
            list.add(p.name);
        }
        return list;
    }

    private List<PlayerRow> rows() {
        List<PlayerRow> list = new ArrayList<>();
        for (Player p : players.values()) {
            list.add(new PlayerRow(p.id, p.name, p.online, p.falseClaims));
        }
        return list;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}

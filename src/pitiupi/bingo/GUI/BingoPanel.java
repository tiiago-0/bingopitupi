package pitiupi.bingo.GUI;

import pitiupi.bingo.control.BingoHost;
import pitiupi.bingo.control.BingoPlayer;
import pitiupi.bingo.control.BingoRules;
import pitiupi.bingo.control.ProtocolLog;
import pitiupi.bingo.net.CheckingMessage;
import pitiupi.bingo.net.Pattern;
import pitiupi.bingo.net.StartMessage;
import pitiupi.bingo.net.VerdictMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Painel da aba central do Bingo: criar ou entrar numa partida, sortear
 * (dono da sala), marcar a cartela e gritar BINGO (jogador), e acompanhar o
 * log do protocolo.
 */
public final class BingoPanel extends JPanel implements ProtocolLog, BingoHost.View, BingoPlayer.View {

    /** Cria o dono da sala já ligado à rede; implementado pelo plugin. */
    public interface HostFactory {
        BingoHost create(String title, Pattern pattern, ProtocolLog log, BingoHost.View view);
    }

    private static final String HOME = "home";
    private static final String HOST = "host";
    private static final String PLAYER = "player";
    private static final String END = "end";

    private static final Color OK = new Color(0x2E7D32);
    private static final Color BAD = new Color(0xC62828);
    private static final Color DRAWN = new Color(0x1565C0);
    private static final Color MARK_OK = new Color(0xA5D6A7);
    private static final Color MARK_BAD = new Color(0xEF9A9A);
    private static final Color FREE_CELL = new Color(0xE0E0E0);

    private final BingoPlayer player;
    private final HostFactory hostFactory;
    private BingoHost host;

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private String currentCard = HOME;
    private final JTextArea logArea = new JTextArea();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS");

    // início
    private final JTextField titleField = new JTextField("Bingo da turma");
    private final JRadioButton fullCard = new JRadioButton(Pattern.FULL_CARD.label() + " (todos os números)", true);
    private final JRadioButton line = new JRadioButton(Pattern.LINE.label() + " (partida mais rápida)");
    private final DefaultListModel<BingoPlayer.Lobby> lobbyModel = new DefaultListModel<>();
    private final JList<BingoPlayer.Lobby> lobbyList = new JList<>(lobbyModel);
    private final JButton joinButton = new JButton("Entrar na partida");
    private final JLabel homeStatus = new JLabel(" ");

    // dono da sala
    private final JLabel hostLast = big("--", 56f);
    private final JLabel hostCount = big("Sala de espera aberta", 16f);
    private final JButton startButton = new JButton("Começar partida");
    private final JButton drawButton = new JButton("Sortear número");
    private final JCheckBox autoBox = new JCheckBox("Sorteio automático a cada");
    private final JSpinner autoSeconds = new JSpinner(new SpinnerNumberModel(5, 2, 30, 1));
    private final JButton cancelButton = new JButton("Cancelar partida");
    private final NumberBoard hostBoard = new NumberBoard();
    private final DefaultTableModel playersTable = table("Jogador", "Conectado", "Bingos falsos");
    private final JTextArea hostVerdict = textBlock(13f, Font.PLAIN);
    private final CardGrid hostVerdictCard = new CardGrid(false);
    private final Set<Integer> hostDrawn = new HashSet<>();

    // jogador
    private final JLabel playerLast = big("--", 56f);
    private final JLabel playerHeader = big(" ", 16f);
    private final CardGrid playerCard = new CardGrid(true);
    private final NumberBoard playerBoard = new NumberBoard();
    private final JButton bingoButton = new JButton("BINGO!");
    private final JLabel playerStatus = new JLabel(" ");
    private final JTextArea playerNews = textBlock(13f, Font.PLAIN);
    private final JButton leaveButton = new JButton("Sair da partida");
    private final Set<Integer> playerDrawn = new HashSet<>();

    // fim
    private final JLabel endHeader = big(" ", 22f);
    private final JTextArea endText = textBlock(16f, Font.PLAIN);

    public BingoPanel(BiFunction<ProtocolLog, BingoPlayer.View, BingoPlayer> playerFactory, HostFactory hostFactory) {
        super(new BorderLayout());
        this.hostFactory = hostFactory;
        this.player = playerFactory.apply(this, this);

        deck.add(buildHome(), HOME);
        deck.add(buildHost(), HOST);
        deck.add(buildPlayer(), PLAYER);
        deck.add(buildEnd(), END);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel logPanel = new JPanel(new BorderLayout());
        JPanel logTop = new JPanel(new BorderLayout());
        logTop.add(new JLabel("  Protocolo BINGO/1: mensagens enviadas (→) e recebidas (←)"), BorderLayout.CENTER);
        JButton clear = new JButton("Limpar");
        clear.addActionListener(e -> logArea.setText(""));
        logTop.add(clear, BorderLayout.EAST);
        logPanel.add(logTop, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, deck, logPanel);
        split.setResizeWeight(0.75);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        showCard(HOME);
    }

    public BingoPlayer player() {
        return player;
    }

    public BingoHost host() {
        return host;
    }

    // ================================================================== telas

    private JComponent buildHome() {
        JPanel create = section("Criar partida (você será o dono da sala e sorteia os números)");
        create.setLayout(new BoxLayout(create, BoxLayout.Y_AXIS));
        create.add(row("Título:", titleField));
        create.add(Box.createVerticalStrut(6));
        create.add(left(new JLabel("Para ganhar, o jogador precisa completar:")));
        ButtonGroup group = new ButtonGroup();
        group.add(fullCard);
        group.add(line);
        create.add(left(fullCard));
        create.add(left(line));
        create.add(Box.createVerticalStrut(6));
        create.add(left(new JLabel("<html>Cartela de 5 × 5 com 75 bolas: B (1 a 15), I (16 a 30), N (31 a 45),"
                + "<br>G (46 a 60) e O (61 a 75), com o centro livre. Cada jogador recebe uma cartela diferente.</html>")));
        create.add(Box.createVerticalStrut(8));
        JButton open = new JButton("Abrir sala de espera");
        open.addActionListener(e -> openLobby());
        create.add(left(open));
        create.add(Box.createVerticalGlue());

        JPanel join = section("Partidas abertas na rede");
        join.setLayout(new BorderLayout(4, 4));
        lobbyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lobbyList.setCellRenderer((list, l, i, sel, focus) -> {
            JLabel label = new JLabel("<html><b>" + esc(l.title()) + "</b>, criada por " + esc(l.hostName())
                    + "<br>Para ganhar: " + l.pattern().label()
                    + "<br>" + plural(l.playerNames().size(), "jogador", "jogadores")
                    + (l.playerNames().isEmpty() ? "" : ": " + esc(String.join(", ", l.playerNames()))) + "</html>");
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            label.setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(sel ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        lobbyList.addListSelectionListener(e -> joinButton.setEnabled(lobbyList.getSelectedValue() != null));
        joinButton.setEnabled(false);
        joinButton.addActionListener(e -> joinSelected());
        join.add(new JScrollPane(lobbyList), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(joinButton, BorderLayout.WEST);
        south.add(homeStatus, BorderLayout.SOUTH);
        join.add(south, BorderLayout.SOUTH);

        JPanel home = new JPanel(new GridLayout(1, 2, 8, 0));
        home.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        home.add(new JScrollPane(create));
        home.add(join);
        return home;
    }

    private JComponent buildHost() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout(12, 0));
        hostLast.setHorizontalAlignment(SwingConstants.CENTER);
        hostLast.setPreferredSize(new Dimension(190, 80));
        hostLast.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));
        top.add(hostLast, BorderLayout.WEST);
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(left(hostCount));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(startButton);
        buttons.add(drawButton);
        buttons.add(autoBox);
        buttons.add(autoSeconds);
        buttons.add(new JLabel("s"));
        buttons.add(cancelButton);
        controls.add(left(buttons));
        top.add(controls, BorderLayout.CENTER);
        p.add(top, BorderLayout.NORTH);

        startButton.addActionListener(e -> {
            if (host != null && !host.start()) {
                hostCount.setText("Espere pelo menos um jogador entrar na sala de espera.");
            }
        });
        drawButton.addActionListener(e -> {
            if (host != null) {
                host.drawNow();
            }
        });
        autoBox.addActionListener(e -> applyAuto());
        autoSeconds.addChangeListener(e -> applyAuto());
        cancelButton.addActionListener(e -> {
            if (host != null) {
                host.cancel();
            }
        });

        JPanel right = new JPanel(new BorderLayout(4, 4));
        JScrollPane playersScroll = new JScrollPane(new JTable(playersTable));
        playersScroll.setPreferredSize(new Dimension(300, 140));
        right.add(playersScroll, BorderLayout.NORTH);
        JPanel verdict = section("Última conferência");
        verdict.setLayout(new BorderLayout(4, 4));
        verdict.add(hostVerdict, BorderLayout.NORTH);
        verdict.add(hostVerdictCard, BorderLayout.CENTER);
        right.add(verdict, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.add(hostBoard, BorderLayout.CENTER);
        center.add(right, BorderLayout.EAST);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildPlayer() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout(12, 0));
        playerLast.setHorizontalAlignment(SwingConstants.CENTER);
        playerLast.setPreferredSize(new Dimension(190, 80));
        playerLast.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));
        top.add(playerLast, BorderLayout.WEST);
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(left(playerHeader));
        info.add(left(new JLabel("Clique nos números sorteados para marcar. Completou? Grite BINGO!")));
        info.add(playerNews);
        top.add(info, BorderLayout.CENTER);
        p.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 0));
        playerCard.setPreferredSize(new Dimension(360, 360));
        center.add(playerCard, BorderLayout.WEST);
        center.add(playerBoard, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);

        bingoButton.setFont(bingoButton.getFont().deriveFont(Font.BOLD, 24f));
        bingoButton.setBackground(OK);
        bingoButton.setForeground(Color.WHITE);
        bingoButton.setPreferredSize(new Dimension(220, 54));
        bingoButton.addActionListener(e -> player.claim(playerCard.marks()));
        leaveButton.addActionListener(e -> {
            player.leave();
            showCard(HOME);
        });
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.add(leaveButton, BorderLayout.WEST);
        south.add(playerStatus, BorderLayout.CENTER);
        south.add(bingoButton, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildEnd() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(left(endHeader));
        top.add(endText);
        p.add(top, BorderLayout.NORTH);
        JButton back = new JButton("Voltar ao início");
        back.addActionListener(e -> {
            host = null;
            showCard(HOME);
        });
        JPanel south = new JPanel();
        south.add(back);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    // ================================================================== ações

    private void openLobby() {
        String title = titleField.getText().isBlank() ? "Bingo da turma" : titleField.getText().strip();
        Pattern pattern = line.isSelected() ? Pattern.LINE : Pattern.FULL_CARD;
        host = hostFactory.create(title, pattern, this, this);
        hostDrawn.clear();
        hostBoard.setDrawn(new int[0], -1);
        hostLast.setText("--");
        hostCount.setText("Sala de espera: " + title + " (para ganhar: " + pattern.label() + ")");
        hostVerdict.setText("Ninguém gritou BINGO ainda.");
        hostVerdictCard.clear();
        playersTable.setRowCount(0);
        applyAuto();
        showCard(HOST);
        host.open();
    }

    private void applyAuto() {
        if (host != null) {
            host.setAuto(autoBox.isSelected(), (Integer) autoSeconds.getValue());
        }
    }

    private void joinSelected() {
        BingoPlayer.Lobby lobby = lobbyList.getSelectedValue();
        if (lobby == null) {
            return;
        }
        homeStatus.setText("Enviando pedido de ENTRADA por TCP para " + lobby.hostName() + "…");
        joinButton.setEnabled(false);
        player.join(lobby.sessionId());
    }

    /** Aba fechada: encerra o que estiver em andamento. */
    public void stopAll() {
        if (host != null) {
            host.cancel();
            host = null;
        }
        player.leave();
        showCard(HOME);
    }

    // ================================================================== BingoHost.View

    @Override
    public void playersChanged(List<BingoHost.PlayerRow> players) {
        ui(() -> {
            playersTable.setRowCount(0);
            for (BingoHost.PlayerRow r : players) {
                playersTable.addRow(new Object[]{r.name(), r.online() ? "sim" : "não", r.falseClaims()});
            }
        });
    }

    @Override
    public void stateChanged(BingoHost.State state) {
        ui(() -> {
            startButton.setVisible(state == BingoHost.State.LOBBY);
            drawButton.setEnabled(state == BingoHost.State.RUNNING);
            cancelButton.setEnabled(state != BingoHost.State.FINISHED);
            if (state == BingoHost.State.RUNNING) {
                hostCount.setText(hostDrawn.size() + " de 75 números sorteados. Partida em andamento.");
            } else if (state == BingoHost.State.CHECKING) {
                hostCount.setText("Alguém gritou BINGO! Sorteio pausado durante a conferência.");
            }
        });
    }

    @Override
    public void drawn(int number, int[] drawn) {
        ui(() -> {
            hostDrawn.clear();
            for (int n : drawn) {
                hostDrawn.add(n);
            }
            hostLast.setText(BingoRules.call(number));
            hostCount.setText(drawn.length + " de 75 números sorteados.");
            hostBoard.setDrawn(drawn, number);
            drawButton.setEnabled(drawn.length < BingoRules.BALLS);
        });
    }

    @Override
    public void verdict(VerdictMessage v) {
        ui(() -> {
            hostVerdict.setText(v.getClaimantName() + " gritou BINGO com " + v.getDrawCount() + " números sorteados.\n"
                    + (v.isValid() ? "BINGO VÁLIDO: " : "BINGO FALSO: ") + v.getReason() + ".");
            hostVerdict.setForeground(v.isValid() ? OK : BAD);
            hostVerdictCard.showVerdict(v.getCard(), v.getMarks(), hostDrawn);
        });
    }

    @Override
    public void finished(List<String> winners, boolean cancelled, int drawCount) {
        ui(() -> showEnd(winners, false, cancelled, cancelled ? "Você cancelou a partida." : null, drawCount, true));
    }

    // ================================================================== BingoPlayer.View

    @Override
    public void lobbiesChanged(List<BingoPlayer.Lobby> lobbies) {
        ui(() -> {
            BingoPlayer.Lobby selected = lobbyList.getSelectedValue();
            lobbyModel.clear();
            for (BingoPlayer.Lobby l : lobbies) {
                lobbyModel.addElement(l);
                if (selected != null && selected.sessionId().equals(l.sessionId())) {
                    lobbyList.setSelectedIndex(lobbyModel.size() - 1);
                }
            }
            joinButton.setEnabled(lobbyList.getSelectedValue() != null);
        });
    }

    @Override
    public void joinResult(boolean accepted, String reason, BingoPlayer.Lobby lobby, int[] card, Pattern pattern) {
        ui(() -> {
            joinButton.setEnabled(lobbyList.getSelectedValue() != null);
            if (!accepted) {
                homeStatus.setText("Não foi possível entrar: " + (reason == null ? "pedido recusado." : reason));
                return;
            }
            homeStatus.setText(" ");
            playerDrawn.clear();
            playerBoard.setDrawn(new int[0], -1);
            playerCard.setCard(card);
            playerCard.setEnabled(false);
            playerLast.setText("--");
            playerHeader.setText(lobby.title() + " (para ganhar: " + pattern.label() + ")");
            playerNews.setText("Você recebeu sua cartela. Aguardando " + lobby.hostName() + " começar a partida…");
            playerNews.setForeground(null);
            playerStatus.setText(" ");
            bingoButton.setEnabled(false);
            leaveButton.setText("Sair da partida");
            showCard(PLAYER);
        });
    }

    @Override
    public void lobbyPlayers(List<String> names) {
        ui(() -> {
            if (PLAYER.equals(currentCard) && player.state() == BingoPlayer.State.LOBBY) {
                playerNews.setText("Você recebeu sua cartela. Na sala: " + String.join(", ", names)
                        + ". Aguardando o dono da sala começar…");
            }
        });
    }

    @Override
    public void started(StartMessage start) {
        ui(() -> {
            playerCard.setEnabled(true);
            bingoButton.setEnabled(true);
            playerNews.setText("A partida começou! Fique de olho nos números.");
        });
    }

    @Override
    public void drawn(int number, int[] drawn, int missedBefore) {
        ui(() -> {
            playerDrawn.clear();
            for (int n : drawn) {
                playerDrawn.add(n);
            }
            playerCard.setEnabled(true);
            bingoButton.setEnabled(true);
            playerLast.setText(BingoRules.call(number));
            playerBoard.setDrawn(drawn, number);
            playerNews.setText(drawn.length + "º número: " + BingoRules.call(number) + "."
                    + (missedBefore > 0 ? " (Pacotes anteriores se perderam, mas a lista completa veio junto.)" : ""));
            playerNews.setForeground(null);
        });
    }

    @Override
    public void checking(CheckingMessage c) {
        ui(() -> {
            playerNews.setText("BINGO de " + c.getClaimantName() + "! O dono da sala está conferindo a cartela…");
            playerNews.setForeground(DRAWN);
        });
    }

    @Override
    public void verdict(VerdictMessage v, boolean mine) {
        ui(() -> {
            if (!mine) {
                playerNews.setText(v.isValid()
                        ? "BINGO de " + v.getClaimantName() + " confirmado!"
                        : "Bingo falso de " + v.getClaimantName() + ": " + v.getReason() + ". O jogo continua.");
                playerNews.setForeground(v.isValid() ? OK : BAD);
            }
        });
    }

    @Override
    public void finished(List<String> winners, boolean iWon, boolean cancelled, String reason, int drawCount) {
        ui(() -> {
            if (winners.isEmpty() && HOME.equals(currentCard)) {
                homeStatus.setText(reason == null ? " " : reason);
                return;
            }
            showEnd(winners, iWon, cancelled, reason, drawCount, false);
        });
    }

    @Override
    public void status(String text, boolean ok) {
        ui(() -> {
            JLabel target = PLAYER.equals(currentCard) ? playerStatus : homeStatus;
            target.setText(text);
            target.setForeground(ok ? null : BAD);
        });
    }

    // ================================================================== ProtocolLog

    @Override
    public void sent(String type, String transport, String to, String detail) {
        append("→ " + pad(type) + " [" + transport + "] para " + to + (detail == null ? "" : " · " + detail));
    }

    @Override
    public void received(String type, String from, String detail) {
        append("← " + pad(type) + " de " + from + (detail == null ? "" : " · " + detail));
    }

    @Override
    public void note(String text) {
        append("• " + text);
    }

    private void append(String line) {
        String stamped = clock.format(new Date()) + "  " + line + "\n";
        ui(() -> {
            logArea.append(stamped);
            if (logArea.getLineCount() > 3000) {
                logArea.setText("");
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ================================================================== componentes

    private void showEnd(List<String> winners, boolean iWon, boolean cancelled, String reason, int drawCount, boolean asHost) {
        String text;
        if (cancelled) {
            endHeader.setText("Partida encerrada");
            text = reason == null ? "A partida foi cancelada." : reason;
        } else if (winners.isEmpty()) {
            endHeader.setText("Fim de jogo");
            text = reason == null ? "A partida terminou sem vencedor." : reason;
        } else {
            endHeader.setText(iWon ? "Você ganhou!" : "BINGO!");
            String names = winners.size() == 1 ? winners.get(0)
                    : String.join(", ", winners.subList(0, winners.size() - 1)) + " e " + winners.get(winners.size() - 1);
            text = (winners.size() == 1 ? "Vencedor: " : "Empate! Vencedores: ") + names + ".\n"
                    + "A partida terminou com " + drawCount + " números sorteados.";
            if (iWon && winners.size() > 1) {
                text += "\nVocê divide a vitória: completaram a cartela com o mesmo número.";
            }
        }
        endText.setText(text);
        showCard(END);
    }

    /** Quadro com os 75 números; os sorteados ficam destacados e o último, em evidência. */
    private static final class NumberBoard extends JPanel {
        private final JLabel[] cells = new JLabel[BingoRules.BALLS + 1];

        NumberBoard() {
            super(new GridLayout(BingoRules.SIZE, 16, 2, 2));
            setBorder(BorderFactory.createTitledBorder("Números sorteados"));
            for (int row = 0; row < BingoRules.SIZE; row++) {
                JLabel letter = new JLabel(String.valueOf(BingoRules.LETTERS.charAt(row)), SwingConstants.CENTER);
                letter.setFont(letter.getFont().deriveFont(Font.BOLD, 18f));
                add(letter);
                for (int i = 1; i <= 15; i++) {
                    int n = row * 15 + i;
                    JLabel cell = new JLabel(String.valueOf(n), SwingConstants.CENTER);
                    cell.setOpaque(true);
                    cell.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
                    cells[n] = cell;
                    add(cell);
                }
            }
            setDrawn(new int[0], -1);
        }

        void setDrawn(int[] drawn, int last) {
            for (int n = 1; n <= BingoRules.BALLS; n++) {
                cells[n].setBackground(Color.WHITE);
                cells[n].setForeground(Color.GRAY);
                cells[n].setFont(cells[n].getFont().deriveFont(Font.PLAIN, 13f));
            }
            for (int n : drawn) {
                cells[n].setBackground(n == last ? Color.ORANGE : DRAWN);
                cells[n].setForeground(n == last ? Color.BLACK : Color.WHITE);
                cells[n].setFont(cells[n].getFont().deriveFont(Font.BOLD, 14f));
            }
        }
    }

    /** Cartela 5 × 5. No modo interativo o jogador marca as casas; no outro mostra uma cartela conferida. */
    private static final class CardGrid extends JPanel {
        private final JToggleButton[] cells = new JToggleButton[BingoRules.CELLS];
        private final boolean interactive;

        CardGrid(boolean interactive) {
            super(new GridLayout(BingoRules.SIZE + 1, BingoRules.SIZE, 3, 3));
            this.interactive = interactive;
            for (char c : BingoRules.LETTERS.toCharArray()) {
                JLabel h = new JLabel(String.valueOf(c), SwingConstants.CENTER);
                h.setFont(h.getFont().deriveFont(Font.BOLD, interactive ? 24f : 14f));
                add(h);
            }
            for (int i = 0; i < BingoRules.CELLS; i++) {
                JToggleButton b = new JToggleButton(" ");
                b.setFont(b.getFont().deriveFont(Font.BOLD, interactive ? 20f : 12f));
                b.setFocusPainted(false);
                b.addActionListener(e -> paintMark(b));
                cells[i] = b;
                add(b);
            }
            clear();
        }

        void clear() {
            for (JToggleButton b : cells) {
                b.setText(" ");
                b.setSelected(false);
                b.setEnabled(false);
                b.setBackground(null);
            }
        }

        void setCard(int[] card) {
            for (int i = 0; i < BingoRules.CELLS; i++) {
                JToggleButton b = cells[i];
                b.setSelected(i == BingoRules.FREE);
                b.setText(i == BingoRules.FREE ? "LIVRE" : String.valueOf(card[i]));
                if (i == BingoRules.FREE) {
                    b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
                }
                b.setToolTipText(i == BingoRules.FREE ? "Casa livre: já vem marcada." : null);
                paintMark(b);
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            for (int i = 0; i < BingoRules.CELLS; i++) {
                cells[i].setEnabled(interactive && enabled && i != BingoRules.FREE);
            }
        }

        boolean[] marks() {
            boolean[] marks = new boolean[BingoRules.CELLS];
            for (int i = 0; i < BingoRules.CELLS; i++) {
                marks[i] = cells[i].isSelected();
            }
            return marks;
        }

        private void paintMark(JToggleButton b) {
            if (!interactive) {
                return;
            }
            b.setBackground(b.isSelected() ? (b == cells[BingoRules.FREE] ? FREE_CELL : MARK_OK) : null);
        }

        /** Mostra a cartela conferida: verde = marcado e sorteado, vermelho = marcado sem ter saído. */
        void showVerdict(int[] card, boolean[] marks, Set<Integer> drawn) {
            for (int i = 0; i < BingoRules.CELLS; i++) {
                JToggleButton b = cells[i];
                b.setEnabled(true);
                b.setSelected(false);
                if (i == BingoRules.FREE) {
                    b.setText("LIVRE");
                    b.setBackground(FREE_CELL);
                } else {
                    b.setText(String.valueOf(card[i]));
                    b.setBackground(!marks[i] ? Color.WHITE : drawn.contains(card[i]) ? MARK_OK : MARK_BAD);
                }
                b.setToolTipText(i == BingoRules.FREE ? null : !marks[i] ? "Não marcado"
                        : drawn.contains(card[i]) ? "Marcado e sorteado" : "Marcado, mas não foi sorteado");
            }
        }
    }

    // ================================================================== apoio

    private void showCard(String card) {
        currentCard = card;
        cards.show(deck, card);
    }

    private static String plural(int n, String singular, String pluralForm) {
        return n + " " + (Math.abs(n) == 1 ? singular : pluralForm);
    }

    private static DefaultTableModel table(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JLabel big(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, size));
        return l;
    }

    private static JTextArea textBlock(float size, int style) {
        JTextArea t = new JTextArea(" ");
        t.setEditable(false);
        t.setFocusable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        t.setBackground(new Color(0, 0, 0, 0));
        t.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        t.setFont(new JLabel().getFont().deriveFont(style, size));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        return t;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static JComponent row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return left(p);
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static String pad(String type) {
        return String.format("%-11s", type);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}

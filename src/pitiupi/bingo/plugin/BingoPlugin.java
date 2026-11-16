package pitiupi.bingo.plugin;

import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.peeractions.PeerActionTarget;
import pitiupi.bingo.GUI.BingoPanel;
import pitiupi.bingo.control.BingoHost;
import pitiupi.bingo.control.BingoPlayer;
import pitiupi.bingo.control.PitiupiTransport;
import pitiupi.bingo.net.AnnounceMessage;
import pitiupi.bingo.net.BingoMessage;
import pitiupi.bingo.net.CheckingMessage;
import pitiupi.bingo.net.ClaimMessage;
import pitiupi.bingo.net.DrawMessage;
import pitiupi.bingo.net.EndMessage;
import pitiupi.bingo.net.JoinAckMessage;
import pitiupi.bingo.net.JoinMessage;
import pitiupi.bingo.net.LeaveMessage;
import pitiupi.bingo.net.StartMessage;
import pitiupi.bingo.net.VerdictMessage;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;

import java.util.List;

/**
 * Bingo multiplayer sobre o Pitiupi.
 *
 * <p>O dono da sala sorteia as cartelas e os números (UDP multicast). Quem
 * completar a cartela grita BINGO por TCP, e o dono confere a cartela antes
 * de declarar a vitória ou o bingo falso. O protocolo está no README.md.</p>
 */
public class BingoPlugin extends Plugin {

    private PitiupiTransport transport;
    private BingoPanel panel;
    private PluginWorkspace workspace;

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace workspace) {
        this.workspace = workspace;
        this.transport = new PitiupiTransport(connectionManager);
        this.panel = new BingoPanel(
                (log, view) -> new BingoPlayer(transport, log, view),
                (title, pattern, log, view) -> new BingoHost(transport, log, view, title, pattern)
        );
        bindShellWorkspaceContent(panel);
        installPeerActions(workspace.peerActions());
    }

    @Override
    public void installPeerActions(PeerActionsRegistrar registrar) {
        registrar.register(PeerAction.of(
                "bingo.open",
                "Abrir o Bingo",
                PeerActionTarget.ALL_PEERS,
                target -> workspace.openOrFocusTab(getIdentifier(), getDisplayName())
        ));
        registrar.register(PeerAction.of(
                "bingo.open.peer",
                "Jogar Bingo",
                PeerActionTarget.SINGLE_PEER,
                target -> workspace.openOrFocusTab(getIdentifier(), getDisplayName())
        ));
    }

    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of(
                AnnounceMessage.class, JoinMessage.class, JoinAckMessage.class, LeaveMessage.class,
                StartMessage.class, DrawMessage.class, ClaimMessage.class, CheckingMessage.class,
                VerdictMessage.class, EndMessage.class
        );
    }

    /**
     * Mensagens para o dono da sala (ENTRADA, SAÍDA, BINGO) vão ao {@link BingoHost};
     * as demais, ao {@link BingoPlayer}. As cópias do próprio multicast que voltam
     * pela rede são descartadas.
     */
    @Override
    public void receiveMessage(Message message) {
        if (!(message instanceof BingoMessage m) || panel == null) {
            return;
        }
        if (m.getVersion() != BingoMessage.PROTOCOL_VERSION || transport.selfId().equals(m.getSenderId())) {
            return;
        }
        if (m.isForHost()) {
            BingoHost host = panel.host();
            if (host != null) {
                host.handle(m);
            }
        } else {
            panel.player().handle(m);
        }
    }

    @Override
    public void onShellTabClosed() {
        if (panel != null) {
            panel.stopAll();
        }
    }

    @Override
    public String getName() {
        return "Bingo";
    }

    @Override
    public String getDisplayName() {
        return "Bingo";
    }

    @Override
    public String getDescription() {
        return "Bingo multiplayer: números sorteados por multicast, BINGO por TCP e conferência da cartela pelo dono da sala.";
    }

    @Override
    public String getAuthor() {
        return "Tiago";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String getIdentifier() {
        return "pitiupi.bingo";
    }
}

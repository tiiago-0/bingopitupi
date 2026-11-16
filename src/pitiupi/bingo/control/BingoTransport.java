package pitiupi.bingo.control;

import pitiupi.bingo.net.BingoMessage;

import java.net.InetAddress;
import java.util.Optional;

/** O que o jogo precisa da rede. A implementação real usa o núcleo; os testes usam uma falsa. */
public interface BingoTransport {

    String selfId();

    String selfName();

    boolean multicast(BingoMessage message);

    boolean unicastUdp(BingoMessage message, InetAddress address);

    boolean tcp(BingoMessage message, InetAddress address);

    /** Endereço de outro participante pela lista de presença do núcleo. */
    Optional<InetAddress> addressOf(String instanceId);
}

package udpgk.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/** UDP unicast exchange-rate server. */
public final class ExchangeRateServer implements AutoCloseable {
    private final DatagramSocket socket;
    private final Set<InetSocketAddress> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private Thread receiverThread;
    private Thread senderThread;

    public ExchangeRateServer(int port) throws SocketException {
        socket = new DatagramSocket(port);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        receiverThread = new Thread(this::receiveLoop, "udp-server-receiver");
        senderThread = new Thread(this::sendLoop, "udp-server-sender");
        receiverThread.start();
        senderThread.start();
        System.out.printf("ExchangeRateServer đang lắng nghe UDP tại cổng %d.%n", socket.getLocalPort());
    }

    private void receiveLoop() {
        byte[] buffer = new byte[Constants.BUFFER_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String command = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8).trim();
                InetSocketAddress client = new InetSocketAddress(packet.getAddress(), packet.getPort());
                if (Constants.SUBSCRIBE.equalsIgnoreCase(command)) {
                    clients.add(client);
                    System.out.printf("Client đăng ký: %s (đang theo dõi: %d)%n", client, clients.size());
                } else if (Constants.UNSUBSCRIBE.equalsIgnoreCase(command)) {
                    clients.remove(client);
                    System.out.printf("Client hủy đăng ký: %s%n", client);
                }
            } catch (SocketException e) {
                if (running) {
                    System.err.println("Lỗi socket server: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi nhận dữ liệu: " + e.getMessage());
                }
            }
        }
    }

    private void sendLoop() {
        while (running) {
            long startedAt = System.nanoTime();
            if (!clients.isEmpty()) {
                sendSnapshot(RateRecord.randomSnapshot());
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            long waitMillis = Math.max(1L, 1_000L - elapsedMillis);
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void sendSnapshot(RateRecord snapshot) {
        byte[] data = snapshot.serialize().getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length);
        for (InetSocketAddress client : clients) {
            packet.setSocketAddress(client);
            try {
                socket.send(packet);
            } catch (IOException e) {
                System.err.printf("Không thể gửi tới %s: %s%n", client, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!running && socket.isClosed()) {
            return;
        }
        running = false;
        socket.close();
        if (senderThread != null) {
            senderThread.interrupt();
        }
        System.out.println("ExchangeRateServer đã dừng.");
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Constants.SERVER_PORT;
        ExchangeRateServer server = new ExchangeRateServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));
        server.start();
        Thread.currentThread().join();
    }
}

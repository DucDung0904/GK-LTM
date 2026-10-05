package udpgk.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import udpgk.server.Constants;
import udpgk.server.RateRecord;

/** Swing UDP client for displaying exchange-rate snapshots. */
@SuppressWarnings("serial")
public final class ExchangeRateClient extends JFrame implements AutoCloseable {
    private final String serverHost;
    private final int serverPort;
    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private DefaultTableModel tableModel;
    private final JButton pauseResumeButton = new JButton("Pause");
    private final JLabel statusLabel = new JLabel("Đang kết nối...");
    private volatile boolean running;
    private Thread receiverThread;

    public ExchangeRateClient(String host, int port) throws IOException {
        super("Exchange Rate Client - UDP Unicast");
        serverHost = host;
        serverPort = port;
        serverAddress = InetAddress.getByName(host);
        socket = new DatagramSocket();
        buildUi();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(720, 430));
        setLayout(new BorderLayout(12, 12));

        JLabel title = new JLabel("TỶ GIÁ NGOẠI TỆ THEO THỜI GIAN THỰC", SwingConstants.CENTER);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        title.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 10));
        add(title, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(new Object[] { "Thời gian", "Tokyo", "Paris", "Seoul" }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(28);
        table.getTableHeader().setReorderingAllowed(false);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pauseResumeButton.setMargin(new Insets(7, 18, 7, 18));
        pauseResumeButton.addActionListener(event -> togglePaused());
        controls.add(pauseResumeButton);
        JButton clearButton = new JButton("Xóa bảng");
        clearButton.setMargin(new Insets(7, 18, 7, 18));
        clearButton.addActionListener(event -> tableModel.setRowCount(0));
        controls.add(clearButton);
        footer.add(controls, BorderLayout.WEST);

        JPanel info = new JPanel(new GridLayout(2, 1));
        info.add(new JLabel("Server: " + serverHost + ":" + serverPort));
        info.add(statusLabel);
        footer.add(info, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        pack();
        setLocationByPlatform(true);
    }

    public void start() throws IOException {
        running = true;
        sendCommand(Constants.SUBSCRIBE);
        receiverThread = new Thread(this::receiveLoop, "udp-client-receiver");
        receiverThread.start();
        statusLabel.setText("Đã đăng ký nhận dữ liệu");
    }

    private void receiveLoop() {
        byte[] buffer = new byte[Constants.BUFFER_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                RateRecord record = RateRecord.parse(new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8));
                if (!paused.get()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!paused.get()) {
                            appendRecord(record);
                        }
                    });
                }
            } catch (SocketException e) {
                if (running) {
                    updateStatus("Lỗi socket: " + e.getMessage());
                }
                break;
            } catch (IOException | IllegalArgumentException e) {
                updateStatus("Gói tin không hợp lệ: " + e.getMessage());
            }
        }
    }

    private void appendRecord(RateRecord record) {
        tableModel.addRow(new Object[] {
                record.time(),
                String.format(Locale.US, "%.2f", record.tokyo()),
                String.format(Locale.US, "%.2f", record.paris()),
                String.format(Locale.US, "%.2f", record.seoul())
        });
        while (tableModel.getRowCount() > Constants.MAX_TABLE_ROWS) {
            tableModel.removeRow(0);
        }
    }

    private void togglePaused() {
        boolean nowPaused = !paused.get();
        paused.set(nowPaused);
        pauseResumeButton.setText(nowPaused ? "Resume" : "Pause");
        updateStatus(nowPaused ? "Đã tạm dừng hiển thị" : "Đang hiển thị dữ liệu mới");
    }

    private void sendCommand(String command) throws IOException {
        byte[] data = command.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, serverAddress, serverPort));
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    @Override
    public void close() {
        if (!running && socket.isClosed()) {
            return;
        }
        running = false;
        try {
            sendCommand(Constants.UNSUBSCRIBE);
        } catch (IOException ignored) {
            // The socket may already be closed during application shutdown.
        }
        socket.close();
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : Constants.SERVER_HOST;
        int port;
        try {
            port = args.length > 1 ? Integer.parseInt(args[1]) : Constants.SERVER_PORT;
            ExchangeRateClient client = new ExchangeRateClient(host, port);
            SwingUtilities.invokeLater(() -> {
                client.setVisible(true);
                try {
                    client.start();
                } catch (IOException e) {
                    client.close();
                    JOptionPane.showMessageDialog(client, "Không thể kết nối server: " + e.getMessage(),
                            "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
                }
            });
        } catch (NumberFormatException | IOException e) {
            JOptionPane.showMessageDialog(null, "Tham số server không hợp lệ: " + e.getMessage(),
                    "Lỗi khởi động", JOptionPane.ERROR_MESSAGE);
        }
    }
}

package vn.edu.p2p.desktop.view.screens;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import vn.edu.p2p.desktop.model.RelayTicketInfo;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Màn hình Devices: Thiết lập kết nối LAN trực tiếp hoặc qua Internet Relay Server.
 * Kiểm tra tính hợp lệ của IP/Hostname, Port và mã ghép nối Relay (12 ký tự) thuần túy ở mức cú pháp.
 */
public class DevicesPanel extends JPanel {

    @FunctionalInterface
    public interface RelayJoinHandler {
        void onJoin(String relayHost, int relayPort, String pairingCode);
    }

    private final JRadioButton rbLan;
    private final JRadioButton rbRelay;

    // LAN Direct controls
    private final JTextField txtHostIp;
    private final JTextField txtPort;
    private final JButton btnConnectLan;

    // Internet Relay controls
    private final JTextField txtRelayHost;
    private final JTextField txtRelayPort;
    private final JButton btnCreateRelayCode;
    private final JTextField txtGeneratedCode;
    private final JButton btnCopyRelayCode;
    private final JLabel lblRelayTtl;
    private final JTextField txtPairingCode;
    private final JButton btnConnectRelay;

    // Session status & disconnect controls
    private final JButton btnDisconnect;
    private final JLabel lblConnectionStatus;
    private final JLabel lblPeerDetails;
    private final JLabel lblInputError;

    private SessionViewModel currentSession = SessionViewModel.createDisconnected();
    private boolean networkEnabled = true;
    private BiConsumer<String, Integer> onConnectLanRequested;
    private BiConsumer<String, Integer> onCreateRelayRequested;
    private RelayJoinHandler onJoinRelayRequested;
    private Runnable onDisconnectRequested;

    public DevicesPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ thiết lập kết nối (LAN trực tiếp & Internet Relay)
        JPanel formCard = createCardPanel("Thiết lập kết nối mới");
        formCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 8, 5, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // A. Chế độ LAN trực tiếp
        rbLan = new JRadioButton("A. Kết nối trực tiếp LAN (IP / Hostname & Port)", true);
        rbRelay = new JRadioButton("B. Kết nối Internet qua Relay Server (Mã ghép nối một lần)");
        ButtonGroup group = new ButtonGroup();
        group.add(rbLan);
        group.add(rbRelay);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        formCard.add(rbLan, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = 1;
        formCard.add(new JLabel("Địa chỉ IP hoặc Hostname:"), gbc);

        gbc.gridx = 1;
        txtHostIp = new JTextField("192.168.1.20", 15);
        formCard.add(txtHostIp, gbc);

        gbc.gridx = 2;
        JPanel portPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portPanel.add(new JLabel("Cổng:"));
        txtPort = new JTextField("52001", 6);
        portPanel.add(txtPort);
        btnConnectLan = new JButton("Kết nối LAN");
        portPanel.add(btnConnectLan);
        formCard.add(portPanel, gbc);

        // B. Chế độ Internet Relay
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        formCard.add(rbRelay, gbc);

        // Relay Host + Port
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = 3;
        formCard.add(new JLabel("Relay Server Host:"), gbc);

        gbc.gridx = 1;
        txtRelayHost = new JTextField("127.0.0.1", 15);
        txtRelayHost.setEnabled(false);
        formCard.add(txtRelayHost, gbc);

        gbc.gridx = 2;
        JPanel relayPortPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        relayPortPanel.add(new JLabel("Cổng Relay:"));
        txtRelayPort = new JTextField("47000", 6);
        txtRelayPort.setEnabled(false);
        relayPortPanel.add(txtRelayPort);
        formCard.add(relayPortPanel, gbc);

        // Máy nhận: Tạo mã ghép nối & hiển thị mã + Sao chép
        gbc.gridx = 0;
        gbc.gridy = 4;
        formCard.add(new JLabel("Máy nhận (Tạo phiên chờ):"), gbc);

        gbc.gridx = 1;
        txtGeneratedCode = new JTextField("", 15);
        txtGeneratedCode.setEditable(false);
        txtGeneratedCode.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        txtGeneratedCode.setToolTipText("Mã ghép nối 12 ký tự nhận từ Relay Server");
        formCard.add(txtGeneratedCode, gbc);

        gbc.gridx = 2;
        JPanel createRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnCreateRelayCode = new JButton("Tạo mã ghép nối");
        btnCreateRelayCode.setEnabled(false);
        btnCopyRelayCode = new JButton("Sao chép mã");
        btnCopyRelayCode.setEnabled(false);
        lblRelayTtl = new JLabel("");
        lblRelayTtl.setFont(lblRelayTtl.getFont().deriveFont(Font.ITALIC, 11f));
        createRow.add(btnCreateRelayCode);
        createRow.add(btnCopyRelayCode);
        createRow.add(lblRelayTtl);
        formCard.add(createRow, gbc);

        // Máy gửi: Nhập mã ghép nối & Kết nối bằng mã
        gbc.gridx = 0;
        gbc.gridy = 5;
        formCard.add(new JLabel("Máy gửi (Nhập mã 12 ký tự):"), gbc);

        gbc.gridx = 1;
        txtPairingCode = new JTextField("", 15);
        txtPairingCode.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        txtPairingCode.setEnabled(false);
        formCard.add(txtPairingCode, gbc);

        gbc.gridx = 2;
        btnConnectRelay = new JButton("Kết nối bằng mã");
        btnConnectRelay.setEnabled(false);
        formCard.add(btnConnectRelay, gbc);

        // Nhắc nhở bảo mật Relay
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 3;
        JLabel lblRelaySecurityNote =
                new JLabel(
                        "<html><i>* Lưu ý bảo mật: Mã ghép nối chỉ để định tuyến qua Relay, KHÔNG phải chứng cứ danh tính. "
                                + "Sau khi ghép nối TCP, bắt buộc đối chiếu vân tay SHA-256 ở mục Bảo mật.</i></html>");
        lblRelaySecurityNote.setFont(lblRelaySecurityNote.getFont().deriveFont(11f));
        formCard.add(lblRelaySecurityNote, gbc);

        // Thông báo lỗi nhập liệu / kết nối
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 3;
        lblInputError = new JLabel("");
        lblInputError.setForeground(new Color(229, 87, 83));
        lblInputError.setFont(lblInputError.getFont().deriveFont(Font.BOLD, 12f));
        formCard.add(lblInputError, gbc);

        // Toggle radio LAN / Relay
        rbLan.addActionListener(
                e -> {
                    if (!networkEnabled) {
                        return;
                    }
                    applyConnectionModeControls();
                    lblInputError.setText("");
                });
        rbRelay.addActionListener(
                e -> {
                    if (!networkEnabled) {
                        return;
                    }
                    applyConnectionModeControls();
                    lblInputError.setText("");
                });

        // 2. Thẻ trạng thái phiên hiện hành
        JPanel sessionCard = createCardPanel("Trạng thái phiên kết nối hiện hành");
        sessionCard.setLayout(new GridBagLayout());
        GridBagConstraints gbcSession = new GridBagConstraints();
        gbcSession.insets = new Insets(6, 8, 6, 8);
        gbcSession.anchor = GridBagConstraints.WEST;

        gbcSession.gridx = 0;
        gbcSession.gridy = 0;
        sessionCard.add(new JLabel("Trạng thái:"), gbcSession);
        gbcSession.gridx = 1;
        lblConnectionStatus = new JLabel("CHƯA KẾT NỐI (DISCONNECTED)");
        lblConnectionStatus.setFont(lblConnectionStatus.getFont().deriveFont(Font.BOLD));
        sessionCard.add(lblConnectionStatus, gbcSession);

        gbcSession.gridx = 0;
        gbcSession.gridy = 1;
        sessionCard.add(new JLabel("Chi tiết đối tác:"), gbcSession);
        gbcSession.gridx = 1;
        lblPeerDetails = new JLabel("Chưa có đối tác kết nối.");
        sessionCard.add(lblPeerDetails, gbcSession);

        gbcSession.gridx = 0;
        gbcSession.gridy = 2;
        gbcSession.gridwidth = 2;
        btnDisconnect = new JButton("Hủy kết nối");
        btnDisconnect.setEnabled(false);
        sessionCard.add(btnDisconnect, gbcSession);

        container.add(formCard);
        container.add(Box.createRigidArea(new Dimension(0, 12)));
        container.add(sessionCard);

        add(container, BorderLayout.NORTH);

        // Sự kiện validation khi bấm nút (không gọi socket blocking trên EDT)
        btnConnectLan.addActionListener(e -> validateAndHandleLanConnect());
        btnCreateRelayCode.addActionListener(e -> validateAndHandleRelayCreate());
        btnConnectRelay.addActionListener(e -> validateAndHandleRelayJoin());
        btnCopyRelayCode.addActionListener(e -> copyGeneratedRelayCodeToClipboard());
        btnDisconnect.addActionListener(
                e -> {
                    clearGeneratedRelayCode();
                    if (onDisconnectRequested != null) {
                        onDisconnectRequested.run();
                    }
                });
    }

    private void applyConnectionModeControls() {
        boolean lanSelected = rbLan.isSelected();
        txtHostIp.setEnabled(lanSelected);
        txtPort.setEnabled(lanSelected);
        btnConnectLan.setEnabled(lanSelected);

        txtRelayHost.setEnabled(!lanSelected);
        txtRelayPort.setEnabled(!lanSelected);
        btnCreateRelayCode.setEnabled(!lanSelected);
        btnCopyRelayCode.setEnabled(!lanSelected && !txtGeneratedCode.getText().isBlank());
        txtPairingCode.setEnabled(!lanSelected);
        btnConnectRelay.setEnabled(!lanSelected);
    }

    public void setOnConnectLanRequested(BiConsumer<String, Integer> onConnectLanRequested) {
        this.onConnectLanRequested = onConnectLanRequested;
    }

    public void setOnCreateRelayRequested(BiConsumer<String, Integer> onCreateRelayRequested) {
        this.onCreateRelayRequested = onCreateRelayRequested;
    }

    public void setOnJoinRelayRequested(RelayJoinHandler onJoinRelayRequested) {
        this.onJoinRelayRequested = onJoinRelayRequested;
    }

    public void setOnDisconnectRequested(Runnable onDisconnectRequested) {
        this.onDisconnectRequested = onDisconnectRequested;
    }

    public void setNetworkEnabled(boolean enabled, String statusMessage, boolean isError) {
        this.networkEnabled = enabled;
        rbLan.setEnabled(enabled);
        rbRelay.setEnabled(enabled);
        if (enabled) {
            applyConnectionModeControls();
            lblInputError.setText("");
            updateSession(currentSession);
        } else {
            txtHostIp.setEnabled(false);
            txtPort.setEnabled(false);
            btnConnectLan.setEnabled(false);
            txtRelayHost.setEnabled(false);
            txtRelayPort.setEnabled(false);
            btnCreateRelayCode.setEnabled(false);
            btnCopyRelayCode.setEnabled(false);
            txtPairingCode.setEnabled(false);
            btnConnectRelay.setEnabled(false);
            btnDisconnect.setEnabled(false);
            clearGeneratedRelayCode();
            if (isError) {
                lblConnectionStatus.setText("LỖI KHỞI TẠO BACKEND — ĐÃ KHÓA KẾT NỐI");
                lblConnectionStatus.setForeground(new Color(229, 87, 83));
                lblPeerDetails.setText("Chức năng mạng bị khóa do khởi tạo backend thất bại.");
                lblInputError.setText("Lỗi: " + statusMessage);
            } else {
                lblConnectionStatus.setText("ĐANG KHỞI TẠO ỨNG DỤNG...");
                lblConnectionStatus.setForeground(new Color(53, 116, 240));
                lblPeerDetails.setText(statusMessage);
                lblInputError.setText("");
            }
        }
    }

    /**
     * Hiển thị mã ghép nối thật trả về từ Relay Server (chỉ hiển thị trên UI, không ghi log).
     */
    public void displayGeneratedRelayTicket(RelayTicketInfo ticket) {
        if (ticket == null || ticket.pairingCode() == null || ticket.pairingCode().isBlank()) {
            clearGeneratedRelayCode();
            return;
        }
        txtGeneratedCode.setText(ticket.pairingCode());
        btnCopyRelayCode.setText("Sao chép mã");
        btnCopyRelayCode.setEnabled(networkEnabled && rbRelay.isSelected());
        int ttlSeconds = Math.max(1, ticket.ttlMillis() / 1000);
        lblRelayTtl.setText("TTL: " + ttlSeconds + "s");
        lblInputError.setText("");
    }

    public void clearGeneratedRelayCode() {
        txtGeneratedCode.setText("");
        btnCopyRelayCode.setText("Sao chép mã");
        btnCopyRelayCode.setEnabled(false);
        lblRelayTtl.setText("");
    }

    private void copyGeneratedRelayCodeToClipboard() {
        String code = txtGeneratedCode.getText().trim();
        if (code.isEmpty()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(code), null);
            btnCopyRelayCode.setText("✓ Đã sao chép");
        } catch (Exception ex) {
            lblInputError.setText("Không thể truy cập Clipboard hệ thống; vui lòng sao chép thủ công.");
        }
    }

    private void validateAndHandleLanConnect() {
        if (!networkEnabled) {
            return;
        }
        String host = txtHostIp.getText().trim();
        String portStr = txtPort.getText().trim();

        if (!ValidationUtils.isValidHostOrIp(host)) {
            lblInputError.setText(
                    "Lỗi: Địa chỉ IP hoặc Hostname không hợp lệ! (Ví dụ: 192.168.1.20 hoặc laptop.local)");
            return;
        }
        if (!ValidationUtils.isValidPort(portStr)) {
            lblInputError.setText("Lỗi: Cổng mạng (Port) phải là số nguyên từ 1 đến 65535!");
            return;
        }

        clearGeneratedRelayCode();
        lblInputError.setText("");
        lblConnectionStatus.setText("ĐANG KẾT NỐI TCP...");
        lblPeerDetails.setText("Đang kết nối tới " + host + ":" + portStr + "...");

        if (onConnectLanRequested != null) {
            try {
                int port = Integer.parseInt(portStr);
                onConnectLanRequested.accept(host, port);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void validateAndHandleRelayCreate() {
        if (!networkEnabled) {
            return;
        }
        String host = txtRelayHost.getText().trim();
        String portStr = txtRelayPort.getText().trim();

        if (!ValidationUtils.isValidHostOrIp(host)) {
            lblInputError.setText("Lỗi: Địa chỉ Relay Server Host không hợp lệ!");
            return;
        }
        if (!ValidationUtils.isValidPort(portStr)) {
            lblInputError.setText("Lỗi: Cổng Relay Server phải là số nguyên từ 1 đến 65535!");
            return;
        }

        clearGeneratedRelayCode();
        lblInputError.setText("");
        lblConnectionStatus.setText("ĐANG KẾT NỐI TỚI RELAY SERVER (CONNECTING)...");
        lblPeerDetails.setText("Đang yêu cầu mã ghép nối từ Relay " + host + ":" + portStr + "...");
        btnDisconnect.setEnabled(true);

        if (onCreateRelayRequested != null) {
            try {
                int port = Integer.parseInt(portStr);
                onCreateRelayRequested.accept(host, port);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void validateAndHandleRelayJoin() {
        if (!networkEnabled) {
            return;
        }
        String host = txtRelayHost.getText().trim();
        String portStr = txtRelayPort.getText().trim();
        String code = txtPairingCode.getText().trim();

        if (!ValidationUtils.isValidHostOrIp(host)) {
            lblInputError.setText("Lỗi: Địa chỉ Relay Server Host không hợp lệ!");
            return;
        }
        if (!ValidationUtils.isValidPort(portStr)) {
            lblInputError.setText("Lỗi: Cổng Relay Server phải là số nguyên từ 1 đến 65535!");
            return;
        }
        if (!ValidationUtils.isValidRelayPairingCode(code)) {
            lblInputError.setText(
                    "Lỗi: Mã ghép nối Relay không hợp lệ! (Phải đủ 12 ký tự chuẩn từ Relay, không gồm 0/1/I/O)");
            return;
        }

        String canonicalCode = ValidationUtils.canonicalizeRelayPairingCode(code);
        clearGeneratedRelayCode();
        lblInputError.setText("");
        lblConnectionStatus.setText("ĐANG GHÉP NỐI QUA RELAY SERVER (CONNECTING)...");
        lblPeerDetails.setText("Đang kết nối tới Relay " + host + ":" + portStr + "...");
        btnDisconnect.setEnabled(true);

        if (onJoinRelayRequested != null) {
            try {
                int port = Integer.parseInt(portStr);
                onJoinRelayRequested.onJoin(host, port, canonicalCode);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public void updateSession(SessionViewModel session) {
        this.currentSession = (session != null) ? session : SessionViewModel.createDisconnected();
        if (!networkEnabled) {
            return;
        }

        if (currentSession.state() == SessionState.ERROR) {
            clearGeneratedRelayCode();
            lblConnectionStatus.setText("THẤT BẠI (FAILED)");
            lblConnectionStatus.setForeground(new Color(229, 87, 83));
            String errMsg =
                    (currentSession.errorMessage() != null && !currentSession.errorMessage().isBlank())
                            ? currentSession.errorMessage()
                            : "Kết nối hoặc xác thực TLS thất bại.";
            lblPeerDetails.setText(
                    currentSession.remoteAddress().isBlank()
                            ? "Không thể thiết lập kết nối."
                            : "Đối tác: " + currentSession.remoteAddress());
            lblInputError.setText("Lỗi: " + errMsg);
            btnDisconnect.setEnabled(false);
            return;
        }

        if (currentSession.state() == SessionState.RELAY_CONNECTING) {
            lblConnectionStatus.setForeground(new Color(53, 116, 240));
            lblConnectionStatus.setText("ĐANG KẾT NỐI RELAY (CONNECTING)");
            lblPeerDetails.setText("Đang kết nối tới " + currentSession.remoteAddress() + "...");
            lblInputError.setText("");
            btnDisconnect.setEnabled(true);
            return;
        }

        if (currentSession.state() == SessionState.WAITING_FOR_PEER) {
            lblConnectionStatus.setForeground(new Color(229, 168, 59));
            lblConnectionStatus.setText("ĐANG CHỜ ĐỐI TÁC (WAITING FOR PEER)");
            lblPeerDetails.setText(
                    "Đã đăng ký trên "
                            + currentSession.remoteAddress()
                            + " — Hãy gửi mã ghép nối cho máy gửi.");
            lblInputError.setText("");
            btnDisconnect.setEnabled(true);
            return;
        }

        if (currentSession.state() == SessionState.TCP_CONNECTED) {
            clearGeneratedRelayCode();
            lblConnectionStatus.setForeground(new Color(229, 168, 59));
            lblConnectionStatus.setText("ĐÃ GHÉP NỐI TCP (TCP PAIRED — CHƯA TLS)");
            lblPeerDetails.setText("Đã nối đường truyền TCP qua " + currentSession.remoteAddress());
            lblInputError.setText("");
            btnDisconnect.setEnabled(true);
            return;
        }

        if (currentSession.state() == SessionState.DISCONNECTED
                || currentSession.state() == SessionState.CLOSED
                || currentSession.sessionId().isBlank()) {
            clearGeneratedRelayCode();
            lblConnectionStatus.setForeground(null);
            lblConnectionStatus.setText(currentSession.state().getDisplayName().toUpperCase());
            lblPeerDetails.setText("Chưa có đối tác kết nối.");
            btnDisconnect.setEnabled(false);
            return;
        }

        clearGeneratedRelayCode();
        lblInputError.setText("");
        if (currentSession.state() == SessionState.SECURE_READY) {
            lblConnectionStatus.setForeground(new Color(73, 156, 84));
        } else if (currentSession.state() == SessionState.TLS_HANDSHAKING) {
            lblConnectionStatus.setForeground(new Color(53, 116, 240));
        } else {
            lblConnectionStatus.setForeground(new Color(229, 168, 59));
        }
        lblConnectionStatus.setText(currentSession.state().getDisplayName().toUpperCase());
        lblPeerDetails.setText(
                "Đối tác: " + currentSession.remoteAddress() + " | Session #" + currentSession.sessionId());
        btnDisconnect.setEnabled(true);
    }

    private JPanel createCardPanel(String title) {
        JPanel card = new JPanel();
        card.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createTitledBorder(
                                BorderFactory.createEtchedBorder(),
                                title,
                                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                                card.getFont().deriveFont(Font.BOLD, 13f)),
                        BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        return card;
    }

    public JButton getBtnConnectLan() {
        return btnConnectLan;
    }

    public JButton getBtnCreateRelayCode() {
        return btnCreateRelayCode;
    }

    public JButton getBtnCopyRelayCode() {
        return btnCopyRelayCode;
    }

    public JButton getBtnConnectRelay() {
        return btnConnectRelay;
    }

    public JButton getBtnDisconnect() {
        return btnDisconnect;
    }

    public JTextField getTxtHostIp() {
        return txtHostIp;
    }

    public JTextField getTxtPort() {
        return txtPort;
    }

    public JTextField getTxtRelayHost() {
        return txtRelayHost;
    }

    public JTextField getTxtRelayPort() {
        return txtRelayPort;
    }

    public JTextField getTxtGeneratedCode() {
        return txtGeneratedCode;
    }

    public JTextField getTxtPairingCode() {
        return txtPairingCode;
    }

    public JLabel getLblInputError() {
        return lblInputError;
    }

    public JLabel getLblConnectionStatus() {
        return lblConnectionStatus;
    }

    public String getHostIp() {
        return txtHostIp.getText().trim();
    }

    public String getPortText() {
        return txtPort.getText().trim();
    }

    public String getPairingCode() {
        return txtPairingCode.getText().trim();
    }

    public void selectRelayMode() {
        rbRelay.doClick();
    }

    public void selectLanMode() {
        rbLan.doClick();
    }
}

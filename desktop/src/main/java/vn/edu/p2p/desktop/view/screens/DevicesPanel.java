package vn.edu.p2p.desktop.view.screens;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Màn hình Devices: Thiết lập kết nối LAN trực tiếp hoặc qua Relay Server.
 * Kiểm tra tính hợp lệ của IP/Hostname, Port và Pairing code thuần túy ở mức cú pháp.
 */
public class DevicesPanel extends JPanel {

    private final JRadioButton rbLan;
    private final JRadioButton rbRelay;
    private final JTextField txtHostIp;
    private final JTextField txtPort;
    private final JTextField txtPairingCode;
    private final JButton btnConnectLan;
    private final JButton btnConnectRelay;
    private final JButton btnDisconnect;
    private final JLabel lblConnectionStatus;
    private final JLabel lblPeerDetails;
    private final JLabel lblInputError;

    private SessionViewModel currentSession = SessionViewModel.createDisconnected();

    public DevicesPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ thiết lập kết nối (LAN & Relay)
        JPanel formCard = createCardPanel("Thiết lập kết nối mới");
        formCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Lựa chọn chế độ
        rbLan = new JRadioButton("Kết nối trực tiếp LAN (IP / Hostname & Port)", true);
        rbRelay = new JRadioButton("Kết nối Internet qua Relay (Mã ghép nối)");
        ButtonGroup group = new ButtonGroup();
        group.add(rbLan);
        group.add(rbRelay);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        formCard.add(rbLan, gbc);

        // LAN Host + Port
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

        // Relay
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        formCard.add(rbRelay, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = 3;
        formCard.add(new JLabel("Mã ghép nối đối tác:"), gbc);

        gbc.gridx = 1;
        txtPairingCode = new JTextField("", 15);
        txtPairingCode.setEnabled(false);
        formCard.add(txtPairingCode, gbc);

        gbc.gridx = 2;
        btnConnectRelay = new JButton("Ghép nối Relay");
        btnConnectRelay.setEnabled(false);
        formCard.add(btnConnectRelay, gbc);

        // Thông báo lỗi nhập liệu
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        lblInputError = new JLabel("");
        lblInputError.setForeground(new Color(229, 87, 83));
        lblInputError.setFont(lblInputError.getFont().deriveFont(Font.BOLD, 12f));
        formCard.add(lblInputError, gbc);

        // Toggle radio
        rbLan.addActionListener(e -> {
            txtHostIp.setEnabled(true);
            txtPort.setEnabled(true);
            btnConnectLan.setEnabled(true);
            txtPairingCode.setEnabled(false);
            btnConnectRelay.setEnabled(false);
            lblInputError.setText("");
        });
        rbRelay.addActionListener(e -> {
            txtHostIp.setEnabled(false);
            txtPort.setEnabled(false);
            btnConnectLan.setEnabled(false);
            txtPairingCode.setEnabled(true);
            btnConnectRelay.setEnabled(true);
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
        btnDisconnect = new JButton("Ngắt kết nối hiện tại");
        btnDisconnect.setEnabled(false);
        sessionCard.add(btnDisconnect, gbcSession);

        container.add(formCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(sessionCard);

        add(container, BorderLayout.NORTH);

        // Sự kiện validation khi click nút (không gọi socket blocking)
        btnConnectLan.addActionListener(e -> validateAndHandleLanConnect());
        btnConnectRelay.addActionListener(e -> validateAndHandleRelayConnect());
    }

    private void validateAndHandleLanConnect() {
        String host = txtHostIp.getText().trim();
        String portStr = txtPort.getText().trim();

        if (!ValidationUtils.isValidHostOrIp(host)) {
            lblInputError.setText("Lỗi: Địa chỉ IP hoặc Hostname không hợp lệ! (Ví dụ: 192.168.1.20 hoặc laptop.local)");
            return;
        }
        if (!ValidationUtils.isValidPort(portStr)) {
            lblInputError.setText("Lỗi: Cổng mạng (Port) phải là số nguyên từ 1 đến 65535!");
            return;
        }

        lblInputError.setText("");
        lblConnectionStatus.setText("ĐANG CHỜ BACKEND (UI-02 Placeholder: Chưa kết nối socket thật)");
        lblPeerDetails.setText("Mục tiêu: " + host + ":" + portStr + " (Chờ Codex hoàn thành M2/TLS)");
    }

    private void validateAndHandleRelayConnect() {
        String code = txtPairingCode.getText().trim();
        if (!ValidationUtils.isValidPairingCode(code)) {
            lblInputError.setText("Lỗi: Mã ghép nối Relay không hợp lệ! (Định dạng: 6-12 ký tự chữ/số, ví dụ: 782-901)");
            return;
        }

        lblInputError.setText("");
        lblConnectionStatus.setText("ĐANG CHỜ BACKEND (UI-02 Placeholder: Chưa kết nối Relay thật)");
        lblPeerDetails.setText("Mã ghép nối: " + code + " (Chờ Codex hoàn thành M6 Relay)");
    }

    public void updateSession(SessionViewModel session) {
        this.currentSession = (session != null) ? session : SessionViewModel.createDisconnected();
        lblConnectionStatus.setText(currentSession.stateDescription());
        if (currentSession.sessionId().isBlank()) {
            lblPeerDetails.setText("Chưa có đối tác kết nối.");
            btnDisconnect.setEnabled(false);
        } else {
            lblPeerDetails.setText("Đối tác: " + currentSession.remoteAddress() + " | Session #" + currentSession.sessionId());
            btnDisconnect.setEnabled(true);
        }
    }

    private JPanel createCardPanel(String title) {
        JPanel card = new JPanel();
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        title,
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        card.getFont().deriveFont(Font.BOLD, 13f)),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        return card;
    }

    public JButton getBtnConnectLan() {
        return btnConnectLan;
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

    public JTextField getTxtPairingCode() {
        return txtPairingCode;
    }

    public JLabel getLblInputError() {
        return lblInputError;
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

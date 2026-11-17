package vn.edu.p2p.desktop.view.screens;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.util.Formatters;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Màn hình Dashboard: Tổng quan kết nối, chọn tệp tin và theo dõi tiến trình gửi nhanh.
 * Nút gửi chỉ được kích hoạt khi phiên ở trạng thái SECURE_READY và tệp tin hợp lệ.
 */
public class DashboardPanel extends JPanel {

    private final JLabel lblLocalDev;
    private final JLabel lblPeerStatus;
    private final JLabel lblSecurityStatus;
    private final JLabel lblSelectedFile;
    private final JLabel lblFileSize;
    private final JLabel lblFilePath;
    private final JLabel lblFileError;
    private final JButton btnChooseFile;
    private final JButton btnSend;
    private final JProgressBar progressBar;
    private final JLabel lblTransferInfo;

    private File currentSelectedFile;
    private SessionViewModel currentSession = SessionViewModel.createDisconnected();
    private boolean backendReady = true;
    private java.util.function.Consumer<File> onSendRequested;

    public DashboardPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // 1. Thẻ thông tin kết nối hiện tại (North)
        JPanel connectionCard = createCardPanel("Thông tin phiên kết nối");
        connectionCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        connectionCard.add(new JLabel("Thiết bị này:"), gbc);
        gbc.gridx = 1;
        lblLocalDev = new JLabel("PC-Desktop (Cổng LAN mặc định: 52001)");
        lblLocalDev.setFont(lblLocalDev.getFont().deriveFont(Font.BOLD));
        connectionCard.add(lblLocalDev, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        connectionCard.add(new JLabel("Đối tác kết nối:"), gbc);
        gbc.gridx = 1;
        lblPeerStatus = new JLabel("Chưa kết nối (Vào mục 'Thiết bị & Mạng' để kết nối)");
        connectionCard.add(lblPeerStatus, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        connectionCard.add(new JLabel("Trạng thái bảo mật:"), gbc);
        gbc.gridx = 1;
        lblSecurityStatus = new JLabel("Chưa kết nối");
        lblSecurityStatus.setForeground(new Color(157, 160, 168));
        connectionCard.add(lblSecurityStatus, gbc);

        // 2. Thẻ chọn tệp tin và gửi (Center)
        JPanel sendCard = createCardPanel("Gửi tệp tin");
        sendCard.setLayout(new BoxLayout(sendCard, BoxLayout.Y_AXIS));

        JPanel fileSelectionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        btnChooseFile = new JButton("Chọn tệp tin từ máy...");
        lblSelectedFile = new JLabel("Chưa chọn tệp tin nào");
        lblSelectedFile.setFont(lblSelectedFile.getFont().deriveFont(Font.ITALIC));
        lblFileSize = new JLabel("");
        fileSelectionRow.add(btnChooseFile);
        fileSelectionRow.add(lblSelectedFile);
        fileSelectionRow.add(lblFileSize);
        sendCard.add(fileSelectionRow);

        // Hiển thị đường dẫn tệp
        JPanel pathRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        lblFilePath = new JLabel("");
        lblFilePath.setFont(lblFilePath.getFont().deriveFont(Font.PLAIN, 11f));
        pathRow.add(lblFilePath);
        sendCard.add(pathRow);

        // Thông báo lỗi nếu file không đọc được
        JPanel errorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        lblFileError = new JLabel("");
        lblFileError.setForeground(new Color(229, 87, 83));
        lblFileError.setFont(lblFileError.getFont().deriveFont(Font.BOLD, 12f));
        errorRow.add(lblFileError);
        sendCard.add(errorRow);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        btnSend = new JButton("Bắt đầu gửi tệp tin");
        // Nút gửi bắt buộc disabled khi chưa có kết nối đã xác minh
        btnSend.setEnabled(false);
        actionRow.add(btnSend);

        JLabel lblHint = new JLabel("*(Nút gửi chỉ khả dụng sau khi phiên TLS đã xác minh vân tay)*");
        lblHint.setFont(lblHint.getFont().deriveFont(Font.ITALIC, 11f));
        actionRow.add(lblHint);
        sendCard.add(actionRow);

        // 3. Thẻ tiến trình truyền tệp gần nhất (South)
        JPanel progressCard = createCardPanel("Tiến trình truyền hiện tại");
        progressCard.setLayout(new BoxLayout(progressCard, BoxLayout.Y_AXIS));

        lblTransferInfo = new JLabel("Chưa có tác vụ truyền tải nào đang hoạt động");
        lblTransferInfo.setFont(lblTransferInfo.getFont().deriveFont(Font.PLAIN, 13f));
        progressCard.add(lblTransferInfo);
        progressCard.add(Box.createRigidArea(new Dimension(0, 8)));

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("0%");
        progressBar.setPreferredSize(new Dimension(Integer.MAX_VALUE, 22));
        progressCard.add(progressBar);

        // Ghép các khối vào Panel chính
        JPanel contentContainer = new JPanel();
        contentContainer.setLayout(new BoxLayout(contentContainer, BoxLayout.Y_AXIS));
        contentContainer.add(connectionCard);
        contentContainer.add(Box.createRigidArea(new Dimension(0, 14)));
        contentContainer.add(sendCard);
        contentContainer.add(Box.createRigidArea(new Dimension(0, 14)));
        contentContainer.add(progressCard);

        add(contentContainer, BorderLayout.NORTH);

        // Sự kiện chọn tệp tin
        btnChooseFile.addActionListener(e -> chooseLocalFile());

        // Sự kiện gửi tệp tin
        btnSend.addActionListener(e -> {
            if (currentSelectedFile != null && onSendRequested != null) {
                onSendRequested.accept(currentSelectedFile);
            }
        });
    }

    public void setOnSendRequested(java.util.function.Consumer<File> onSendRequested) {
        this.onSendRequested = onSendRequested;
    }

    public void setListeningPort(int port) {
        if (port > 0) {
            lblLocalDev.setText("PC-Desktop (Đang lắng nghe LAN cổng: " + port + ")");
        } else {
            lblLocalDev.setText("PC-Desktop (Chưa mở cổng lắng nghe)");
        }
    }

    public void setBackendInitializing(String statusText) {
        this.backendReady = false;
        lblLocalDev.setText("PC-Desktop (Đang khởi tạo danh tính & mạng...)");
        lblPeerStatus.setText(statusText);
        lblSecurityStatus.setText("⟳ " + statusText);
        lblSecurityStatus.setForeground(new Color(53, 116, 240));
        updateSendButtonState();
    }

    public void setBackendFailed(String errorText) {
        this.backendReady = false;
        lblLocalDev.setText("PC-Desktop (Lỗi khởi tạo backend — Đã khóa mạng)");
        lblPeerStatus.setText("Không thể kết nối mạng vì backend chưa khởi tạo được");
        lblSecurityStatus.setText("✕ Không khả dụng: " + errorText);
        lblSecurityStatus.setForeground(new Color(229, 87, 83));
        updateSendButtonState();
    }

    public void setBackendReady(int listenPort) {
        this.backendReady = true;
        setListeningPort(listenPort);
        updateSessionState(currentSession);
    }

    private void chooseLocalFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Chọn tệp tin cần gửi qua P2P");
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            setSelectedFile(fileChooser.getSelectedFile());
        }
    }

    public void setSelectedFile(File selected) {
        if (selected == null) {
            return;
        }
        if (!ValidationUtils.isValidReadableFile(selected)) {
            lblFileError.setText("Lỗi: Tệp tin không tồn tại, là thư mục hoặc không có quyền đọc!");
            lblSelectedFile.setText("Tệp không hợp lệ");
            lblFileSize.setText("");
            lblFilePath.setText(selected.getAbsolutePath());
            currentSelectedFile = null;
        } else {
            currentSelectedFile = selected;
            lblFileError.setText("");
            lblSelectedFile.setText(selected.getName());
            lblSelectedFile.setFont(lblSelectedFile.getFont().deriveFont(Font.BOLD));
            lblFileSize.setText("(" + Formatters.formatBytes(selected.length()) + ")");
            lblFilePath.setText("Đường dẫn: " + selected.getAbsolutePath());
        }
        updateSendButtonState();
    }

    public void updateSessionState(SessionViewModel session) {
        this.currentSession = (session != null) ? session : SessionViewModel.createDisconnected();
        if (!backendReady) {
            updateSendButtonState();
            return;
        }
        if (currentSession.state() == SessionState.ERROR) {
            String peer = currentSession.remoteAddress().isBlank() ? "Chưa kết nối" : currentSession.remoteAddress();
            lblPeerStatus.setText(peer);
            String err = (currentSession.errorMessage() != null && !currentSession.errorMessage().isBlank())
                    ? currentSession.errorMessage() : "Lỗi xác thực / Handshake thất bại";
            lblSecurityStatus.setText("✕ " + err);
            lblSecurityStatus.setForeground(new Color(229, 87, 83));
        } else if (currentSession.state() == SessionState.RELAY_CONNECTING) {
            lblPeerStatus.setText(currentSession.remoteAddress());
            lblSecurityStatus.setText("⟳ Đang kết nối tới Relay Server (Chưa có TLS)");
            lblSecurityStatus.setForeground(new Color(53, 116, 240));
        } else if (currentSession.state() == SessionState.WAITING_FOR_PEER) {
            lblPeerStatus.setText(currentSession.remoteAddress());
            lblSecurityStatus.setText("! Đang chờ đối tác ghép nối qua Relay (Chưa có TLS)");
            lblSecurityStatus.setForeground(new Color(229, 168, 59));
        } else if (currentSession.state() == SessionState.TCP_CONNECTED) {
            lblPeerStatus.setText(currentSession.remoteAddress());
            lblSecurityStatus.setText("! Đã ghép nối TCP — Chưa thiết lập TLS (Chưa bảo mật)");
            lblSecurityStatus.setForeground(new Color(229, 168, 59));
        } else if (currentSession.sessionId().isBlank() || currentSession.state() == SessionState.DISCONNECTED) {
            lblPeerStatus.setText("Chưa kết nối (Vào mục 'Thiết bị & Mạng' để kết nối)");
            lblSecurityStatus.setText("Chưa kết nối");
            lblSecurityStatus.setForeground(new Color(157, 160, 168));
        } else {
            lblPeerStatus.setText(currentSession.remoteAddress() + " (Phiên #" + currentSession.sessionId() + ")");
            switch (currentSession.state()) {
                case TCP_CONNECTED -> {
                    lblSecurityStatus.setText("! Đã nối TCP — Chưa thiết lập TLS (Chưa bảo mật)");
                    lblSecurityStatus.setForeground(new Color(229, 168, 59));
                }
                case WAITING_FOR_FINGERPRINT -> {
                    lblSecurityStatus.setText("! Chờ nhập vân tay đối tác (Kênh độc lập)");
                    lblSecurityStatus.setForeground(new Color(229, 168, 59));
                }
                case TLS_HANDSHAKING -> {
                    lblSecurityStatus.setText("⟳ Đang bắt tay Mutual TLS 1.3...");
                    lblSecurityStatus.setForeground(new Color(53, 116, 240));
                }
                case SECURE_READY -> {
                    lblSecurityStatus.setText("✓ ĐÃ XÁC THỰC MUTUAL TLS 1.3 (An toàn)");
                    lblSecurityStatus.setForeground(new Color(73, 156, 84));
                }
                default -> {
                    lblSecurityStatus.setText(currentSession.state().getDisplayName());
                    lblSecurityStatus.setForeground(new Color(157, 160, 168));
                }
            }
        }
        updateSendButtonState();
    }

    public void updateTransferProgress(TransferViewModel transfer) {
        if (transfer == null || transfer.state() == TransferViewModel.TransferState.IDLE) {
            lblTransferInfo.setText("Chưa có tác vụ truyền tải nào đang hoạt động");
            progressBar.setValue(0);
            progressBar.setString("0%");
            return;
        }
        lblTransferInfo.setText(transfer.direction().getDisplayName() + ": " + transfer.fileName()
                + " — " + transfer.getFormattedSizeProgress() + " (" + transfer.state().getDisplayName() + ")");
        progressBar.setValue(transfer.getProgressPercentage());
        progressBar.setString(transfer.getProgressPercentage() + "% — " + transfer.getFormattedSpeed());
    }

    private void updateSendButtonState() {
        boolean canSend = backendReady
                && currentSession.isReadyToTransfer()
                && currentSelectedFile != null
                && ValidationUtils.isValidReadableFile(currentSelectedFile);
        btnSend.setEnabled(canSend);
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

    public JButton getBtnSend() {
        return btnSend;
    }

    public JButton getBtnChooseFile() {
        return btnChooseFile;
    }

    public File getCurrentSelectedFile() {
        return currentSelectedFile;
    }

    public JLabel getLblFileError() {
        return lblFileError;
    }

    public JLabel getLblSecurityStatus() {
        return lblSecurityStatus;
    }
}

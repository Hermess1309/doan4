package vn.edu.p2p.desktop.view.screens;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.util.Formatters;
import vn.edu.p2p.desktop.util.ValidationUtils;
import vn.edu.p2p.desktop.view.dialogs.FingerprintVerificationDialog;

/**
 * Màn hình Security: Quản lý chứng chỉ bảo mật TLS 1.3 và điều phối luồng xác minh vân tay M2.
 * Ngăn chặn tuyệt đối việc tự động đánh dấu an toàn khi chưa bắt tay TLS 1.3 thành công.
 */
public class SecurityPanel extends JPanel {

    private final JLabel lblVerificationBadge;
    private final JLabel lblSessionInfo;
    private final JLabel lblTlsDetail;
    private final JTextArea txtLocalFingerprint;
    private final JButton btnOpenVerificationDialog;
    private final JButton btnCopyLocalFingerprint;
    private final JLabel lblCopyStatus;

    private String localFingerprintCanonical = "";
    private SessionViewModel currentSession = SessionViewModel.createDisconnected();
    private boolean backendReady = true;
    private BiConsumer<String, String> onTlsHandshakeRequested;

    public SecurityPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ trạng thái bảo mật phiên hiện hành
        JPanel statusCard = createCardPanel("Trạng thái bảo mật phiên mạng hiện hành");
        statusCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        statusCard.add(new JLabel("Cơ chế bảo vệ:"), gbc);
        gbc.gridx = 1;
        lblTlsDetail = new JLabel("TLS 1.3 Mutual Authentication (SHA-256 Pinning trước handshake)");
        lblTlsDetail.setFont(lblTlsDetail.getFont().deriveFont(Font.BOLD));
        statusCard.add(lblTlsDetail, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        statusCard.add(new JLabel("Phiên kết nối:"), gbc);
        gbc.gridx = 1;
        lblSessionInfo = new JLabel("Chưa có phiên kết nối");
        statusCard.add(lblSessionInfo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        statusCard.add(new JLabel("Tình trạng an toàn:"), gbc);
        gbc.gridx = 1;
        lblVerificationBadge = new JLabel("[!] CHƯA KẾT NỐI (DISCONNECTED)");
        lblVerificationBadge.setFont(lblVerificationBadge.getFont().deriveFont(Font.BOLD));
        lblVerificationBadge.setForeground(new Color(157, 160, 168));
        statusCard.add(lblVerificationBadge, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        btnOpenVerificationDialog = new JButton("Đối chiếu vân tay & Bắt tay TLS 1.3");
        btnOpenVerificationDialog.setEnabled(false); // Sáng khi ở trạng thái TCP_CONNECTED hoặc WAITING_FOR_FINGERPRINT
        btnRow.add(btnOpenVerificationDialog);
        statusCard.add(btnRow, gbc);

        // 2. Thẻ hiển thị vân tay của máy này (Local Certificate Fingerprint)
        JPanel localFpCard = createCardPanel("Vân tay chứng chỉ của máy này (Local SHA-256)");
        localFpCard.setLayout(new BorderLayout(8, 8));

        JLabel lblLocalFpHint = new JLabel("<html>Chuỗi SHA-256 DER fingerprint sinh từ danh tính cục bộ (Local Identity). "
                + "Hãy đọc hoặc gửi chuỗi này cho đối tác qua kênh độc lập:</html>");
        localFpCard.add(lblLocalFpHint, BorderLayout.NORTH);

        txtLocalFingerprint = new JTextArea(Formatters.formatFingerprint(""));
        txtLocalFingerprint.setRows(4);
        txtLocalFingerprint.setEditable(false);
        txtLocalFingerprint.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        txtLocalFingerprint.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        localFpCard.add(new JScrollPane(txtLocalFingerprint), BorderLayout.CENTER);

        JPanel copyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        btnCopyLocalFingerprint = new JButton("Sao chép mã vân tay");
        btnCopyLocalFingerprint.setEnabled(false);
        lblCopyStatus = new JLabel("");
        lblCopyStatus.setFont(lblCopyStatus.getFont().deriveFont(Font.PLAIN, 11f));
        lblCopyStatus.setForeground(new Color(73, 156, 84));
        copyRow.add(btnCopyLocalFingerprint);
        copyRow.add(lblCopyStatus);
        localFpCard.add(copyRow, BorderLayout.SOUTH);

        container.add(statusCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(localFpCard);

        add(container, BorderLayout.NORTH);

        // Sự kiện sao chép mã vân tay SHA-256 chuẩn (64 ký tự hex) vào Clipboard
        btnCopyLocalFingerprint.addActionListener(e -> copyLocalFingerprintToClipboard());

        // Sự kiện mở Dialog xác minh thật khi có phiên
        btnOpenVerificationDialog.addActionListener(e -> {
            if (!backendReady || currentSession.sessionId().isBlank()) {
                return;
            }
            final String dialogSessionId = currentSession.sessionId();
            final long dialogGeneration = currentSession.sessionGeneration();
            Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
            FingerprintVerificationDialog dialog = new FingerprintVerificationDialog(
                    owner, dialogSessionId, currentSession.remoteAddress(),
                    currentSession.localFingerprintRaw(), currentSession.expectedRemoteFingerprintRaw(),
                    false); // isPreviewMode = false

            dialog.setOnFingerprintConfirmed(verifiedPin -> {
                // Kiểm tra phiên chưa bị thay đổi trong lúc hộp thoại đang mở
                if (!backendReady
                        || !dialogSessionId.equals(currentSession.sessionId())
                        || dialogGeneration != currentSession.sessionGeneration()) {
                    return;
                }
                // Người dùng đã nhập và xác nhận pin vân tay đối tác -> chuyển sang trạng thái bắt tay TLS
                // Tuyệt đối KHÔNG tự ý chuyển sang SECURE_READY ở đây, phải chờ kết quả handshake từ backend!
                updateSession(new SessionViewModel(
                        dialogSessionId,
                        currentSession.remoteAddress(),
                        currentSession.localFingerprintRaw(),
                        verifiedPin,
                        currentSession.isRelay(),
                        SessionState.TLS_HANDSHAKING,
                        "",
                        dialogGeneration));

                if (onTlsHandshakeRequested != null) {
                    onTlsHandshakeRequested.accept(dialogSessionId, verifiedPin);
                }
            });

            dialog.setVisible(true);
        });
    }

    public void setBackendInitializing(String message) {
        this.backendReady = false;
        this.localFingerprintCanonical = "";
        txtLocalFingerprint.setText("(Đang khởi tạo danh tính cục bộ...)");
        txtLocalFingerprint.setCaretPosition(0);
        lblSessionInfo.setText(message);
        lblVerificationBadge.setText("[⟳] " + message.toUpperCase());
        lblVerificationBadge.setForeground(new Color(53, 116, 240));
        btnOpenVerificationDialog.setEnabled(false);
        btnCopyLocalFingerprint.setEnabled(false);
        lblCopyStatus.setText("");
    }

    public void setBackendFailed(String errorMessage) {
        this.backendReady = false;
        this.localFingerprintCanonical = "";
        txtLocalFingerprint.setText("(Không khả dụng — Khởi tạo danh tính cục bộ thất bại)");
        txtLocalFingerprint.setCaretPosition(0);
        lblSessionInfo.setText("Lỗi khởi tạo backend bảo mật");
        lblVerificationBadge.setText("[✕] LỖI KHỞI TẠO BẢO MẬT: " + errorMessage);
        lblVerificationBadge.setForeground(new Color(229, 87, 83));
        btnOpenVerificationDialog.setEnabled(false);
        btnCopyLocalFingerprint.setEnabled(false);
        lblCopyStatus.setText("");
    }

    public void setBackendReady(String localFingerprintRaw) {
        this.backendReady = true;
        setLocalFingerprint(localFingerprintRaw);
        updateSession(currentSession);
    }

    public void updateSession(SessionViewModel session) {
        this.currentSession = (session != null) ? session : SessionViewModel.createDisconnected();
        if (!backendReady) {
            btnOpenVerificationDialog.setEnabled(false);
            return;
        }
        String sId = currentSession.sessionId();
        String addr = currentSession.remoteAddress();

        if (currentSession.state() == SessionState.ERROR) {
            lblSessionInfo.setText(sId.isBlank() ? (addr.isBlank() ? "Chưa có phiên kết nối" : addr) : (addr + " (Session #" + sId + ")"));
            String err = (currentSession.errorMessage() != null && !currentSession.errorMessage().isBlank())
                    ? currentSession.errorMessage() : "Xác thực thất bại / Lỗi kết nối";
            lblVerificationBadge.setText("[✕] " + err.toUpperCase());
            lblVerificationBadge.setForeground(new Color(229, 87, 83));
            btnOpenVerificationDialog.setEnabled(false);
            return;
        }

        if (currentSession.state() == SessionState.RELAY_CONNECTING
                || currentSession.state() == SessionState.WAITING_FOR_PEER) {
            lblSessionInfo.setText(addr.isBlank() ? "Đang chờ ghép nối Relay" : addr);
            lblVerificationBadge.setText("[⟳] " + currentSession.state().getDisplayName().toUpperCase());
            lblVerificationBadge.setForeground(new Color(229, 168, 59));
            btnOpenVerificationDialog.setEnabled(false);
            return;
        }

        if (sId.isBlank() || currentSession.state() == SessionState.DISCONNECTED) {
            lblSessionInfo.setText("Chưa có phiên kết nối");
            lblVerificationBadge.setText("[!] CHƯA KẾT NỐI (DISCONNECTED)");
            lblVerificationBadge.setForeground(new Color(157, 160, 168));
            btnOpenVerificationDialog.setEnabled(false);
            return;
        }

        lblSessionInfo.setText(addr + " (Session #" + sId + ")");

        switch (currentSession.state()) {
            case TCP_CONNECTED -> {
                lblVerificationBadge.setText("[!] ĐÃ NỐI TCP — CHƯA THIẾT LẬP TLS (Chưa bảo mật)");
                lblVerificationBadge.setForeground(new Color(229, 168, 59));
                btnOpenVerificationDialog.setEnabled(true);
            }
            case WAITING_FOR_FINGERPRINT -> {
                lblVerificationBadge.setText("[!] CHỜ NHẬP VÂN TAY ĐỐI TÁC QUA KÊNH ĐỘC LẬP");
                lblVerificationBadge.setForeground(new Color(229, 168, 59));
                btnOpenVerificationDialog.setEnabled(true);
            }
            case TLS_HANDSHAKING -> {
                lblVerificationBadge.setText("[⟳] ĐANG BẮT TAY MUTUAL TLS 1.3...");
                lblVerificationBadge.setForeground(new Color(53, 116, 240));
                btnOpenVerificationDialog.setEnabled(false);
            }
            case SECURE_READY -> {
                lblVerificationBadge.setText("[✓] KẾT NỐI AN TOÀN ĐÃ XÁC THỰC (TLS 1.3 MUTUAL)");
                lblVerificationBadge.setForeground(new Color(73, 156, 84));
                btnOpenVerificationDialog.setEnabled(false);
            }
            case CLOSED -> {
                lblVerificationBadge.setText("[!] PHIÊN ĐÃ ĐÓNG (CLOSED)");
                lblVerificationBadge.setForeground(new Color(157, 160, 168));
                btnOpenVerificationDialog.setEnabled(false);
            }
            default -> {
                lblVerificationBadge.setText("[!] " + currentSession.state().getDisplayName());
                btnOpenVerificationDialog.setEnabled(false);
            }
        }
    }

    public void setLocalFingerprint(String localFingerprintRaw) {
        this.localFingerprintCanonical = ValidationUtils.canonicalizeFingerprint(localFingerprintRaw);
        txtLocalFingerprint.setText(Formatters.formatFingerprint(localFingerprintRaw));
        txtLocalFingerprint.setCaretPosition(0);
        btnCopyLocalFingerprint.setEnabled(!this.localFingerprintCanonical.isEmpty());
        lblCopyStatus.setText("");
    }

    private void copyLocalFingerprintToClipboard() {
        if (localFingerprintCanonical.isEmpty()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(localFingerprintCanonical), null);
            lblCopyStatus.setForeground(new Color(73, 156, 84));
            lblCopyStatus.setText("✓ Đã sao chép mã vân tay (64 ký tự hex)");
        } catch (HeadlessException | IllegalStateException ex) {
            lblCopyStatus.setForeground(new Color(229, 87, 83));
            lblCopyStatus.setText("Không thể truy cập Clipboard hệ thống.");
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

    public JButton getBtnOpenVerificationDialog() {
        return btnOpenVerificationDialog;
    }

    public JButton getBtnCopyLocalFingerprint() {
        return btnCopyLocalFingerprint;
    }

    public String getLocalFingerprintCanonical() {
        return localFingerprintCanonical;
    }

    public JLabel getLblCopyStatus() {
        return lblCopyStatus;
    }

    public JLabel getLblVerificationBadge() {
        return lblVerificationBadge;
    }

    public JTextArea getTxtLocalFingerprint() {
        return txtLocalFingerprint;
    }

    public void setOnTlsHandshakeRequested(BiConsumer<String, String> onTlsHandshakeRequested) {
        this.onTlsHandshakeRequested = onTlsHandshakeRequested;
    }
}

package vn.edu.p2p.desktop.view.dialogs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import vn.edu.p2p.desktop.util.Formatters;

/**
 * Hộp thoại bắt buộc xác minh vân tay chứng chỉ (Certificate Fingerprint) TLS 1.3.
 * Chỉ khi người dùng đối chiếu và bấm xác nhận thì trạng thái mới chuyển sang READY.
 * Nghiêm cấm xác nhận nếu chưa có dữ liệu thật từ backend hoặc đang ở chế độ xem trước.
 */
public class FingerprintVerificationDialog extends JDialog {

    private boolean verified = false;
    private final boolean isPreviewMode;

    private final JLabel lblSessionDetails;
    private final JLabel lblPreviewWarning;
    private final JTextArea txtRemoteFingerprint;
    private final JTextArea txtLocalFingerprint;
    private final JButton btnCopyRemote;
    private final JButton btnCopyLocal;
    private final JButton btnVerify;
    private final JButton btnReject;

    private final String rawRemoteFp;
    private final String rawLocalFp;

    public FingerprintVerificationDialog(Frame owner, String sessionId, String peerAddress,
            String remoteFingerprintRaw, String localFingerprintRaw, boolean isPreviewMode) {
        super(owner, "Xác minh Bảo mật Kết nối (TLS 1.3)", true);
        this.isPreviewMode = isPreviewMode;
        this.rawRemoteFp = (remoteFingerprintRaw != null) ? remoteFingerprintRaw : "";
        this.rawLocalFp = (localFingerprintRaw != null) ? localFingerprintRaw : "";

        setSize(700, 520);
        setMinimumSize(new Dimension(580, 440));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(16, 16));

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        // 1. Cảnh báo kênh độc lập
        JPanel warningCard = new JPanel(new BorderLayout(10, 8));
        warningCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(229, 168, 59), 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel lblWarningTitle = new JLabel("BẮT BUỘC ĐỐI CHIẾU VÂN TAY QUA KÊNH ĐỘC LẬP");
        lblWarningTitle.setFont(lblWarningTitle.getFont().deriveFont(Font.BOLD, 13f));
        lblWarningTitle.setForeground(new Color(229, 168, 59));

        JLabel lblWarningText = new JLabel("<html>Để chống tấn công nghe lén (Man-In-The-Middle), hãy gọi điện hoặc "
                + "gặp mặt trực tiếp để so sánh chuỗi SHA-256 dưới đây với đối tác trước khi truyền dữ liệu.</html>");

        warningCard.add(lblWarningTitle, BorderLayout.NORTH);
        warningCard.add(lblWarningText, BorderLayout.CENTER);

        // Cảnh báo nếu đang là Preview Mode
        lblPreviewWarning = new JLabel("");
        if (isPreviewMode) {
            lblPreviewWarning.setText("<html><b style='color:#E55753;'>[CHẾ ĐỘ XEM TRƯỚC GIAO DIỆN — KHÔNG THỰC HIỆN XÁC MINH THẬT]</b></html>");
            warningCard.add(lblPreviewWarning, BorderLayout.SOUTH);
        }

        mainPanel.add(warningCard);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 2. Chi tiết phiên
        String sessionDisplay = (sessionId != null && !sessionId.isBlank()) ? sessionId : "Chưa có session ID";
        String peerDisplay = (peerAddress != null && !peerAddress.isBlank()) ? peerAddress : "Chưa có đối tác";
        lblSessionDetails = new JLabel("Phiên kết nối: " + sessionDisplay + " | Địa chỉ đối tác: " + peerDisplay);
        lblSessionDetails.setFont(lblSessionDetails.getFont().deriveFont(Font.BOLD));
        mainPanel.add(lblSessionDetails);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 3. Khối hiển thị Vân tay
        JPanel fpPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;

        // Remote Fingerprint Header + Copy
        gbc.gridx = 0;
        gbc.gridy = 0;
        JPanel remoteHeader = new JPanel(new BorderLayout());
        JLabel lblRemoteTitle = new JLabel("1. Vân tay của Đối tác (Remote SHA-256 Fingerprint):");
        lblRemoteTitle.setFont(lblRemoteTitle.getFont().deriveFont(Font.BOLD));
        btnCopyRemote = new JButton("Sao chép");
        remoteHeader.add(lblRemoteTitle, BorderLayout.WEST);
        remoteHeader.add(btnCopyRemote, BorderLayout.EAST);
        fpPanel.add(remoteHeader, gbc);

        gbc.gridy = 1;
        txtRemoteFingerprint = createFingerprintArea(Formatters.formatFingerprint(rawRemoteFp));
        fpPanel.add(new JScrollPane(txtRemoteFingerprint), gbc);

        // Local Fingerprint Header + Copy
        gbc.gridy = 2;
        JPanel localHeader = new JPanel(new BorderLayout());
        JLabel lblLocalTitle = new JLabel("2. Vân tay của Bạn (Đọc chuỗi này cho đối tác đối chiếu):");
        lblLocalTitle.setFont(lblLocalTitle.getFont().deriveFont(Font.BOLD));
        btnCopyLocal = new JButton("Sao chép");
        localHeader.add(lblLocalTitle, BorderLayout.WEST);
        localHeader.add(btnCopyLocal, BorderLayout.EAST);
        fpPanel.add(localHeader, gbc);

        gbc.gridy = 3;
        txtLocalFingerprint = createFingerprintArea(Formatters.formatFingerprint(rawLocalFp));
        fpPanel.add(new JScrollPane(txtLocalFingerprint), gbc);

        mainPanel.add(fpPanel);
        add(mainPanel, BorderLayout.CENTER);

        // 4. Các nút hành động phía dưới
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 12));
        buttonPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 63, 65)));

        btnReject = new JButton("✕ Từ chối & Ngắt kết nối");
        btnVerify = new JButton("✓ Tôi đã xác thực trùng khớp");
        btnVerify.setFont(btnVerify.getFont().deriveFont(Font.BOLD));

        // Quy tắc an toàn: Không cho phép bấm xác minh nếu ở Preview Mode hoặc thiếu dữ liệu
        boolean hasValidData = sessionId != null && !sessionId.isBlank()
                && remoteFingerprintRaw != null && !remoteFingerprintRaw.isBlank();
        btnVerify.setEnabled(!isPreviewMode && hasValidData);

        btnReject.addActionListener(e -> {
            verified = false;
            dispose();
        });

        btnVerify.addActionListener(e -> {
            if (!isPreviewMode && hasValidData) {
                verified = true;
                dispose();
            }
        });

        btnCopyRemote.addActionListener(e -> copyToClipboard(rawRemoteFp, btnCopyRemote));
        btnCopyLocal.addActionListener(e -> copyToClipboard(rawLocalFp, btnCopyLocal));

        buttonPanel.add(btnReject);
        buttonPanel.add(btnVerify);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void copyToClipboard(String text, JButton sourceButton) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            String originalText = sourceButton.getText();
            sourceButton.setText("✓ Đã sao chép");
            Timer timer = new Timer(1500, e -> sourceButton.setText(originalText));
            timer.setRepeats(false);
            timer.start();
        } catch (Exception ex) {
            sourceButton.setToolTipText("Lỗi clipboard: " + ex.getMessage());
        }
    }

    private JTextArea createFingerprintArea(String content) {
        JTextArea area = new JTextArea(content);
        area.setRows(3);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return area;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isPreviewMode() {
        return isPreviewMode;
    }

    public JButton getBtnVerify() {
        return btnVerify;
    }

    public JButton getBtnReject() {
        return btnReject;
    }

    public JButton getBtnCopyRemote() {
        return btnCopyRemote;
    }

    public JButton getBtnCopyLocal() {
        return btnCopyLocal;
    }
}

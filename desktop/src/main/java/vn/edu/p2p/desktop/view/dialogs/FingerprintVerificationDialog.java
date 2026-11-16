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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import vn.edu.p2p.desktop.util.Formatters;

/**
 * Hộp thoại bắt buộc xác minh vân tay chứng chỉ (Certificate Fingerprint) TLS 1.3.
 * Chỉ khi người dùng đối chiếu và bấm xác nhận thì trạng thái mới chuyển sang READY.
 */
public class FingerprintVerificationDialog extends JDialog {

    private boolean verified = false;

    private final JLabel lblSessionDetails;
    private final JTextArea txtRemoteFingerprint;
    private final JTextArea txtLocalFingerprint;
    private final JButton btnVerify;
    private final JButton btnReject;

    public FingerprintVerificationDialog(Frame owner, String sessionId, String peerAddress,
            String remoteFingerprintRaw, String localFingerprintRaw) {
        super(owner, "Xác minh Bảo mật Kết nối (TLS 1.3)", true);
        setSize(680, 480);
        setMinimumSize(new Dimension(560, 400));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(16, 16));

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        // 1. Cảnh báo kênh độc lập
        JPanel warningCard = new JPanel(new BorderLayout(10, 10));
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
        mainPanel.add(warningCard);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 12)));

        // 2. Chi tiết phiên
        lblSessionDetails = new JLabel("Phiên kết nối: " + (sessionId != null ? sessionId : "N/A")
                + " | Địa chỉ đối tác: " + (peerAddress != null ? peerAddress : "N/A"));
        lblSessionDetails.setFont(lblSessionDetails.getFont().deriveFont(Font.BOLD));
        mainPanel.add(lblSessionDetails);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 3. Khối hiển thị Vân tay
        JPanel fpPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;

        // Remote Fingerprint
        gbc.gridx = 0;
        gbc.gridy = 0;
        JLabel lblRemoteTitle = new JLabel("1. Vân tay của Đối tác (Remote SHA-256 Fingerprint):");
        lblRemoteTitle.setFont(lblRemoteTitle.getFont().deriveFont(Font.BOLD));
        fpPanel.add(lblRemoteTitle, gbc);

        gbc.gridy = 1;
        txtRemoteFingerprint = createFingerprintArea(Formatters.formatFingerprint(remoteFingerprintRaw));
        fpPanel.add(new JScrollPane(txtRemoteFingerprint), gbc);

        // Local Fingerprint
        gbc.gridy = 2;
        JLabel lblLocalTitle = new JLabel("2. Vân tay của Bạn (Đọc chuỗi này cho đối tác đối chiếu):");
        lblLocalTitle.setFont(lblLocalTitle.getFont().deriveFont(Font.BOLD));
        fpPanel.add(lblLocalTitle, gbc);

        gbc.gridy = 3;
        txtLocalFingerprint = createFingerprintArea(Formatters.formatFingerprint(localFingerprintRaw));
        fpPanel.add(new JScrollPane(txtLocalFingerprint), gbc);

        mainPanel.add(fpPanel);
        add(mainPanel, BorderLayout.CENTER);

        // 4. Các nút hành động phía dưới
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 12));
        buttonPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 63, 65)));

        btnReject = new JButton("✕ Từ chối & Ngắt kết nối");
        btnVerify = new JButton("✓ Tôi đã xác thực trùng khớp");
        btnVerify.setFont(btnVerify.getFont().deriveFont(Font.BOLD));

        btnReject.addActionListener(e -> {
            verified = false;
            dispose();
        });

        btnVerify.addActionListener(e -> {
            verified = true;
            dispose();
        });

        buttonPanel.add(btnReject);
        buttonPanel.add(btnVerify);
        add(buttonPanel, BorderLayout.SOUTH);
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

    public void updateFingerprints(String remoteRaw, String localRaw) {
        txtRemoteFingerprint.setText(Formatters.formatFingerprint(remoteRaw));
        txtLocalFingerprint.setText(Formatters.formatFingerprint(localRaw));
    }
}

package vn.edu.p2p.desktop.view.screens;

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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import vn.edu.p2p.desktop.util.Formatters;
import vn.edu.p2p.desktop.view.dialogs.FingerprintVerificationDialog;

/**
 * Màn hình Security: Quản lý chứng chỉ bảo mật TLS 1.3 và trạng thái xác thực vân tay.
 */
public class SecurityPanel extends JPanel {

    private final JLabel lblVerificationBadge;
    private final JTextArea txtLocalFingerprint;
    private final JButton btnOpenVerificationDialog;

    public SecurityPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ trạng thái xác minh phiên
        JPanel statusCard = createCardPanel("Trạng thái bảo mật phiên hiện hành");
        statusCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        statusCard.add(new JLabel("Chứng chỉ & Mã hóa:"), gbc);
        gbc.gridx = 1;
        JLabel lblTls = new JLabel("TLS 1.3 (Mutual TLS - Pinning SHA-256)");
        lblTls.setFont(lblTls.getFont().deriveFont(Font.BOLD));
        statusCard.add(lblTls, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        statusCard.add(new JLabel("Tình trạng xác minh:"), gbc);
        gbc.gridx = 1;
        lblVerificationBadge = new JLabel("[!] CHƯA CÓ KẾT NỐI ĐƯỢC XÁC THỰC");
        lblVerificationBadge.setFont(lblVerificationBadge.getFont().deriveFont(Font.BOLD));
        lblVerificationBadge.setForeground(new Color(229, 168, 59));
        statusCard.add(lblVerificationBadge, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        btnOpenVerificationDialog = new JButton("Mở hộp thoại đối chiếu vân tay (Fingerprint Dialog)");
        btnOpenVerificationDialog.setEnabled(false); // Sáng khi có phiên VERIFYING
        statusCard.add(btnOpenVerificationDialog, gbc);

        // 2. Thẻ hiển thị vân tay của máy này (Local Certificate Fingerprint)
        JPanel localFpCard = createCardPanel("Vân tay chứng chỉ của máy này (Local SHA-256)");
        localFpCard.setLayout(new BorderLayout(8, 8));

        JLabel lblLocalFpHint = new JLabel("Chuỗi SHA-256 DER fingerprint được sinh từ keystore cục bộ của máy:");
        localFpCard.add(lblLocalFpHint, BorderLayout.NORTH);

        txtLocalFingerprint = new JTextArea(Formatters.formatFingerprint(""));
        txtLocalFingerprint.setRows(3);
        txtLocalFingerprint.setEditable(false);
        txtLocalFingerprint.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        txtLocalFingerprint.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        localFpCard.add(new JScrollPane(txtLocalFingerprint), BorderLayout.CENTER);

        container.add(statusCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(localFpCard);

        add(container, BorderLayout.NORTH);

        // Sự kiện mở dialog (hỗ trợ kiểm thử trực tiếp)
        btnOpenVerificationDialog.addActionListener(e -> {
            Frame owner = (Frame) SwingUtilities.getWindowAncestor(this);
            FingerprintVerificationDialog dialog = new FingerprintVerificationDialog(
                    owner, "demo-session", "192.168.1.20:52001",
                    "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF",
                    "3F11A8C95D44201B7E6088912C5540AA00192837465564738291A0B1C2D3E4F5");
            dialog.setVisible(true);
        });
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

    public void setVerificationStatus(boolean verified, String remoteAddress) {
        if (verified) {
            lblVerificationBadge.setText("[✓] ĐÃ XÁC THỰC AN TOÀN (" + remoteAddress + ")");
            lblVerificationBadge.setForeground(new Color(73, 156, 84));
        } else {
            lblVerificationBadge.setText("[!] CHƯA CÓ KẾT NỐI ĐƯỢC XÁC THỰC");
            lblVerificationBadge.setForeground(new Color(229, 168, 59));
        }
    }
}

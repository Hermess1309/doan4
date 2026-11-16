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
import java.util.function.Consumer;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import vn.edu.p2p.desktop.util.Formatters;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Hộp thoại đối chiếu và nhập Expected Remote Fingerprint trước khi bắt tay TLS 1.3.
 * Tuân thủ đúng kiến trúc bảo mật M2: Pinning diễn ra trước TLS handshake,
 * người dùng nhập fingerprint của đối tác nhận được qua kênh độc lập.
 */
public class FingerprintVerificationDialog extends JDialog {

    private boolean submitted = false;
    private String verifiedRemoteFingerprint = "";
    private final boolean isPreviewMode;
    private final String sessionId;

    private final JLabel lblSessionDetails;
    private final JLabel lblPreviewWarning;
    private final JTextArea txtLocalFingerprint;
    private final JTextArea txtRemoteFingerprintInput;
    private final JLabel lblValidationFeedback;
    private final JButton btnCopyLocal;
    private final JButton btnStartTls;
    private final JButton btnCancel;

    private final String rawLocalFp;
    private Consumer<String> onFingerprintConfirmed;

    public FingerprintVerificationDialog(Frame owner, String sessionId, String peerAddress,
            String localFingerprintRaw, String initialExpectedRemoteFp, boolean isPreviewMode) {
        super(owner, "Đối chiếu Vân tay & Pinning TLS 1.3", true);
        this.isPreviewMode = isPreviewMode;
        this.sessionId = (sessionId != null) ? sessionId.trim() : "";
        this.rawLocalFp = (localFingerprintRaw != null) ? localFingerprintRaw.trim() : "";

        setSize(720, 560);
        setMinimumSize(new Dimension(600, 480));
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

        JLabel lblWarningTitle = new JLabel("QUY TRÌNH BẢO MẬT: ĐỐI CHIẾU VÂN TAY QUA KÊNH ĐỘC LẬP");
        lblWarningTitle.setFont(lblWarningTitle.getFont().deriveFont(Font.BOLD, 13f));
        lblWarningTitle.setForeground(new Color(229, 168, 59));

        JLabel lblWarningText = new JLabel("<html>Để chống tấn công nghe lén (Man-In-The-Middle), hai bên <b>bắt buộc</b> "
                + "gọi điện hoặc gặp mặt trực tiếp để đối chiếu chuỗi SHA-256 fingerprint trước khi bắt tay TLS 1.3.</html>");

        warningCard.add(lblWarningTitle, BorderLayout.NORTH);
        warningCard.add(lblWarningText, BorderLayout.CENTER);

        // Cảnh báo nếu đang là Preview Mode
        lblPreviewWarning = new JLabel("");
        if (isPreviewMode) {
            lblPreviewWarning.setText("<html><b style='color:#E55753;'>[CHẾ ĐỘ XEM TRƯỚC GIAO DIỆN — KHÔNG THỰC HIỆN BẮT TAY THẬT]</b></html>");
            warningCard.add(lblPreviewWarning, BorderLayout.SOUTH);
        }

        mainPanel.add(warningCard);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 2. Chi tiết phiên
        String sessionDisplay = !this.sessionId.isBlank() ? this.sessionId : "Chưa có session ID";
        String peerDisplay = (peerAddress != null && !peerAddress.isBlank()) ? peerAddress : "Chưa có đối tác";
        lblSessionDetails = new JLabel("Phiên kết nối: " + sessionDisplay + " | Địa chỉ đối tác: " + peerDisplay);
        lblSessionDetails.setFont(lblSessionDetails.getFont().deriveFont(Font.BOLD));
        mainPanel.add(lblSessionDetails);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 3. Khối hiển thị & Nhập Vân tay
        JPanel fpPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;

        // 3.1. Local Fingerprint (Đọc cho đối tác)
        gbc.gridx = 0;
        gbc.gridy = 0;
        JPanel localHeader = new JPanel(new BorderLayout());
        JLabel lblLocalTitle = new JLabel("1. Vân tay của Bạn (Đọc chuỗi SHA-256 này cho đối tác đối chiếu):");
        lblLocalTitle.setFont(lblLocalTitle.getFont().deriveFont(Font.BOLD));
        btnCopyLocal = new JButton("Sao chép");
        localHeader.add(lblLocalTitle, BorderLayout.WEST);
        localHeader.add(btnCopyLocal, BorderLayout.EAST);
        fpPanel.add(localHeader, gbc);

        gbc.gridy = 1;
        txtLocalFingerprint = createFingerprintArea(Formatters.formatFingerprint(rawLocalFp), false);
        fpPanel.add(new JScrollPane(txtLocalFingerprint), gbc);

        // 3.2. Expected Remote Fingerprint (Nhập từ đối tác)
        gbc.gridy = 2;
        JPanel remoteHeader = new JPanel(new BorderLayout());
        JLabel lblRemoteTitle = new JLabel("2. Nhập vân tay của Đối tác (Hỏi đối tác qua kênh độc lập và dán vào đây):");
        lblRemoteTitle.setFont(lblRemoteTitle.getFont().deriveFont(Font.BOLD));
        remoteHeader.add(lblRemoteTitle, BorderLayout.WEST);
        fpPanel.add(remoteHeader, gbc);

        gbc.gridy = 3;
        String initRemote = (initialExpectedRemoteFp != null) ? initialExpectedRemoteFp : "";
        txtRemoteFingerprintInput = createFingerprintArea(initRemote, true);
        fpPanel.add(new JScrollPane(txtRemoteFingerprintInput), gbc);

        // Nhãn phản hồi kiểm tra cú pháp vân tay
        gbc.gridy = 4;
        lblValidationFeedback = new JLabel("Vui lòng nhập đủ 64 ký tự hex hoặc 32 cặp byte phân tách bằng dấu hai chấm.");
        lblValidationFeedback.setFont(lblValidationFeedback.getFont().deriveFont(Font.PLAIN, 11f));
        lblValidationFeedback.setForeground(new Color(157, 160, 168));
        fpPanel.add(lblValidationFeedback, gbc);

        mainPanel.add(fpPanel);
        add(mainPanel, BorderLayout.CENTER);

        // 4. Các nút hành động phía dưới
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 12));
        buttonPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 63, 65)));

        btnCancel = new JButton("✕ Hủy bỏ & Đóng");
        btnStartTls = new JButton("✓ Pin vân tay & Bắt tay TLS 1.3");
        btnStartTls.setFont(btnStartTls.getFont().deriveFont(Font.BOLD));
        btnStartTls.setEnabled(false); // Khóa cho đến khi nhập đúng vân tay

        btnCancel.addActionListener(e -> {
            submitted = false;
            dispose();
        });

        btnStartTls.addActionListener(e -> {
            String inputFp = txtRemoteFingerprintInput.getText().trim();
            if (ValidationUtils.isValidFingerprint(inputFp) && !isPreviewMode && !this.sessionId.isBlank()) {
                submitted = true;
                verifiedRemoteFingerprint = ValidationUtils.canonicalizeFingerprint(inputFp);
                if (onFingerprintConfirmed != null) {
                    onFingerprintConfirmed.accept(verifiedRemoteFingerprint);
                }
                dispose();
            }
        });

        btnCopyLocal.addActionListener(e -> copyToClipboard(rawLocalFp, btnCopyLocal));

        // Lắng nghe thay đổi khi gõ hoặc dán vân tay
        txtRemoteFingerprintInput.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validateRemoteInput();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateRemoteInput();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateRemoteInput();
            }
        });

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnStartTls);
        add(buttonPanel, BorderLayout.SOUTH);

        // Kiểm tra dữ liệu khởi tạo ban đầu
        validateRemoteInput();
    }

    private void validateRemoteInput() {
        String input = txtRemoteFingerprintInput.getText().trim();
        if (input.isEmpty()) {
            lblValidationFeedback.setText("Vui lòng nhập đủ 64 ký tự hex hoặc 32 cặp byte phân tách bằng dấu hai chấm.");
            lblValidationFeedback.setForeground(new Color(157, 160, 168));
            btnStartTls.setEnabled(false);
            return;
        }

        if (ValidationUtils.isValidFingerprint(input)) {
            lblValidationFeedback.setText("✓ Định dạng vân tay SHA-256 hợp lệ (32 byte). Sẵn sàng bắt tay TLS 1.3.");
            lblValidationFeedback.setForeground(new Color(73, 156, 84));
            boolean canSubmit = !isPreviewMode && !sessionId.isBlank();
            btnStartTls.setEnabled(canSubmit);
        } else {
            lblValidationFeedback.setText("✕ Lỗi: Vân tay phải đủ 32 byte (64 ký tự hex SHA-256)! Không chấp nhận mã rút gọn.");
            lblValidationFeedback.setForeground(new Color(229, 87, 83));
            btnStartTls.setEnabled(false);
        }
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

    private JTextArea createFingerprintArea(String content, boolean editable) {
        JTextArea area = new JTextArea(content);
        area.setRows(3);
        area.setEditable(editable);
        area.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return area;
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public String getVerifiedRemoteFingerprint() {
        return verifiedRemoteFingerprint;
    }

    public boolean isPreviewMode() {
        return isPreviewMode;
    }

    public JButton getBtnStartTls() {
        return btnStartTls;
    }

    public JButton getBtnCancel() {
        return btnCancel;
    }

    public JButton getBtnCopyLocal() {
        return btnCopyLocal;
    }

    public JTextArea getTxtRemoteFingerprintInput() {
        return txtRemoteFingerprintInput;
    }

    public JLabel getLblValidationFeedback() {
        return lblValidationFeedback;
    }

    public void setOnFingerprintConfirmed(Consumer<String> onFingerprintConfirmed) {
        this.onFingerprintConfirmed = onFingerprintConfirmed;
    }
}

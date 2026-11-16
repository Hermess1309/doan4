package vn.edu.p2p.desktop.view.screens;

import java.awt.BorderLayout;
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
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import vn.edu.p2p.desktop.util.ThemeManager;

/**
 * Màn hình Settings: Cấu hình thư mục tải xuống, giao diện Dark/Light và thông tin ứng dụng.
 */
public class SettingsPanel extends JPanel {

    private final JTextField txtDownloadDir;
    private final JButton btnBrowseDir;
    private final JRadioButton rbDark;
    private final JRadioButton rbLight;

    public SettingsPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ Thư mục lưu trữ tệp nhận
        JPanel dirCard = createCardPanel("Thư mục lưu trữ tệp tin tải về");
        dirCard.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 8));

        String defaultDownload = System.getProperty("user.home") + File.separator + "Downloads";
        txtDownloadDir = new JTextField(defaultDownload, 32);
        txtDownloadDir.setEditable(false);
        btnBrowseDir = new JButton("Duyệt thư mục...");

        dirCard.add(new JLabel("Đường dẫn:"));
        dirCard.add(txtDownloadDir);
        dirCard.add(btnBrowseDir);

        btnBrowseDir.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(txtDownloadDir.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Chọn thư mục lưu tệp tin");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                txtDownloadDir.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        // 2. Thẻ Giao diện (Theme)
        JPanel themeCard = createCardPanel("Chủ đề giao diện (FlatLaf Theme)");
        themeCard.setLayout(new FlowLayout(FlowLayout.LEFT, 16, 8));

        rbDark = new JRadioButton("Giao diện tối (FlatDarkLaf)", ThemeManager.isDark());
        rbLight = new JRadioButton("Giao diện sáng (FlatLightLaf)", !ThemeManager.isDark());
        ButtonGroup themeGroup = new ButtonGroup();
        themeGroup.add(rbDark);
        themeGroup.add(rbLight);

        themeCard.add(rbDark);
        themeCard.add(rbLight);

        rbDark.addActionListener(e -> ThemeManager.setTheme(ThemeManager.ThemeMode.DARK, this));
        rbLight.addActionListener(e -> ThemeManager.setTheme(ThemeManager.ThemeMode.LIGHT, this));

        // 3. Thẻ Thông tin đồ án
        JPanel aboutCard = createCardPanel("Thông tin Đồ án 4");
        aboutCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        aboutCard.add(new JLabel("Đề tài:"), gbc);
        gbc.gridx = 1;
        JLabel lblTitle = new JLabel("P2P File Transfer ngang hàng bảo mật E2E (TLS 1.3)");
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD));
        aboutCard.add(lblTitle, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        aboutCard.add(new JLabel("Phiên bản:"), gbc);
        gbc.gridx = 1;
        aboutCard.add(new JLabel("v0.1.0-alpha (Java 21 + FlatLaf 3.5.4)"), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        aboutCard.add(new JLabel("Đội ngũ phát triển:"), gbc);
        gbc.gridx = 1;
        aboutCard.add(new JLabel("Sinh viên, Codex (Backend) và Antigravity (Desktop UI)"), gbc);

        container.add(dirCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(themeCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(aboutCard);

        add(container, BorderLayout.NORTH);
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

    public String getDownloadDirectory() {
        return txtDownloadDir.getText().trim();
    }
}

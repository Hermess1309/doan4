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
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import vn.edu.p2p.desktop.model.AppSettings;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.util.ValidationUtils;

/**
 * Màn hình Settings: Cấu hình thư mục tải xuống, giao diện Dark/Light và thông tin ứng dụng.
 * Lưu trữ bằng Java Preferences tiêu chuẩn, kiểm tra quyền ghi của thư mục.
 */
public class SettingsPanel extends JPanel {

    private final JTextField txtDownloadDir;
    private final JButton btnBrowseDir;
    private final JLabel lblDirStatus;
    private final JRadioButton rbDark;
    private final JRadioButton rbLight;

    public SettingsPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ Thư mục lưu trữ tệp nhận
        JPanel dirCard = createCardPanel("Thư mục lưu trữ tệp tin tải về");
        dirCard.setLayout(new BoxLayout(dirCard, BoxLayout.Y_AXIS));

        JPanel browseRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        txtDownloadDir = new JTextField(AppSettings.getDownloadDirectory(), 32);
        txtDownloadDir.setEditable(false);
        btnBrowseDir = new JButton("Duyệt thư mục...");

        browseRow.add(new JLabel("Đường dẫn:"));
        browseRow.add(txtDownloadDir);
        browseRow.add(btnBrowseDir);
        dirCard.add(browseRow);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        lblDirStatus = new JLabel("Thư mục hợp lệ và có quyền ghi.");
        lblDirStatus.setFont(lblDirStatus.getFont().deriveFont(Font.PLAIN, 11f));
        lblDirStatus.setForeground(new Color(73, 156, 84));
        statusRow.add(lblDirStatus);
        dirCard.add(statusRow);

        btnBrowseDir.addActionListener(e -> chooseDownloadDirectory());

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

        container.add(dirCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(themeCard);
        container.add(Box.createRigidArea(new Dimension(0, 16)));
        container.add(aboutCard);

        add(container, BorderLayout.NORTH);
    }

    private void chooseDownloadDirectory() {
        JFileChooser chooser = new JFileChooser(txtDownloadDir.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Chọn thư mục lưu tệp tin tải về");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (selected == null) {
                return;
            }
            if (ValidationUtils.isValidWritableDirectory(selected)) {
                boolean saved = AppSettings.setDownloadDirectory(selected.getAbsolutePath());
                if (saved) {
                    txtDownloadDir.setText(selected.getAbsolutePath());
                    lblDirStatus.setText("Thư mục hợp lệ và đã được lưu cấu hình.");
                    lblDirStatus.setForeground(new Color(73, 156, 84));
                }
            } else {
                lblDirStatus.setText("Lỗi: Thư mục không tồn tại hoặc không có quyền ghi!");
                lblDirStatus.setForeground(new Color(229, 87, 83));
            }
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

    public String getDownloadDirectory() {
        return txtDownloadDir.getText().trim();
    }

    public JLabel getLblDirStatus() {
        return lblDirStatus;
    }

    public JButton getBtnBrowseDir() {
        return btnBrowseDir;
    }
}

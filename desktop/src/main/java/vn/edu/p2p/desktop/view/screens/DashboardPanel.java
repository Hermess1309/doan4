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
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import vn.edu.p2p.desktop.util.Formatters;

/**
 * Màn hình Dashboard: Tổng quan kết nối, chọn tệp tin và theo dõi tiến trình gửi nhanh.
 */
public class DashboardPanel extends JPanel {

    private final JLabel lblPeerStatus;
    private final JLabel lblSelectedFile;
    private final JLabel lblFileSize;
    private final JButton btnChooseFile;
    private final JButton btnSend;
    private final JProgressBar progressBar;
    private final JLabel lblTransferInfo;

    private File currentSelectedFile;

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
        JLabel lblLocalDev = new JLabel("PC-Desktop (Cổng mặc định: 52001)");
        lblLocalDev.setFont(lblLocalDev.getFont().deriveFont(Font.BOLD));
        connectionCard.add(lblLocalDev, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        connectionCard.add(new JLabel("Đối tác kết nối:"), gbc);
        gbc.gridx = 1;
        lblPeerStatus = new JLabel("Chưa kết nối (Vui lòng vào mục 'Thiết bị' để kết nối)");
        connectionCard.add(lblPeerStatus, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        connectionCard.add(new JLabel("Trạng thái bảo mật:"), gbc);
        gbc.gridx = 1;
        JLabel lblSec = new JLabel("Chưa xác minh vân tay chứng chỉ (TLS 1.3)");
        connectionCard.add(lblSec, gbc);

        // 2. Thẻ chọn tệp tin và gửi (Center)
        JPanel sendCard = createCardPanel("Gửi tệp tin");
        sendCard.setLayout(new BoxLayout(sendCard, BoxLayout.Y_AXIS));

        JPanel fileSelectionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        btnChooseFile = new JButton("Chọn tệp tin từ máy...");
        lblSelectedFile = new JLabel("Chưa chọn tệp tin nào");
        lblSelectedFile.setFont(lblSelectedFile.getFont().deriveFont(Font.ITALIC));
        lblFileSize = new JLabel("");
        fileSelectionRow.add(btnChooseFile);
        fileSelectionRow.add(lblSelectedFile);
        fileSelectionRow.add(lblFileSize);
        sendCard.add(fileSelectionRow);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        btnSend = new JButton("Bắt đầu gửi tệp tin");
        // Nút gửi mặc định bị vô hiệu hóa khi chưa kết nối và chưa xác thực
        btnSend.setEnabled(false);
        actionRow.add(btnSend);
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
    }

    private void chooseLocalFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Chọn tệp tin cần gửi qua P2P");
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            currentSelectedFile = fileChooser.getSelectedFile();
            lblSelectedFile.setText(currentSelectedFile.getName());
            lblSelectedFile.setFont(lblSelectedFile.getFont().deriveFont(Font.BOLD));
            lblFileSize.setText("(" + Formatters.formatBytes(currentSelectedFile.length()) + ")");
            // Ghi chú: Nút gửi vẫn giữ disabled nếu chưa có kết nối peer được xác minh
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

    public JButton getBtnSend() {
        return btnSend;
    }

    public JButton getBtnChooseFile() {
        return btnChooseFile;
    }

    public File getCurrentSelectedFile() {
        return currentSelectedFile;
    }
}

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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import vn.edu.p2p.desktop.model.TransferViewModel;

/**
 * Màn hình Transfers: Quản lý chi tiết tiến trình truyền file, tốc độ, hủy và tiếp tục (resume).
 * Cập nhật giao diện an toàn thông qua TransferViewModel.
 */
public class TransfersPanel extends JPanel {

    private final JLabel lblFileName;
    private final JLabel lblFileSize;
    private final JLabel lblTransferDirection;
    private final JLabel lblSpeed;
    private final JLabel lblEta;
    private final JLabel lblState;
    private final JLabel lblError;
    private final JProgressBar progressBar;
    private final JButton btnCancel;
    private final JButton btnResume;
    private final JButton btnOpenFolder;

    private TransferViewModel currentTransfer = TransferViewModel.createIdle();

    public TransfersPanel() {
        setLayout(new BorderLayout(16, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        // 1. Thẻ chi tiết tiến trình truyền
        JPanel card = createCardPanel("Tiến trình tệp tin hiện hành");
        card.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 10, 6, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Tên file
        gbc.gridx = 0;
        gbc.gridy = 0;
        card.add(new JLabel("Tệp tin:"), gbc);
        gbc.gridx = 1;
        lblFileName = new JLabel("(Chưa có tệp tin đang truyền)");
        lblFileName.setFont(lblFileName.getFont().deriveFont(Font.BOLD, 14f));
        card.add(lblFileName, gbc);

        // Kích thước & Bytes đã truyền
        gbc.gridx = 0;
        gbc.gridy = 1;
        card.add(new JLabel("Dung lượng đã truyền:"), gbc);
        gbc.gridx = 1;
        lblFileSize = new JLabel("0 B / 0 B");
        card.add(lblFileSize, gbc);

        // Hướng truyền
        gbc.gridx = 0;
        gbc.gridy = 2;
        card.add(new JLabel("Chiều truyền:"), gbc);
        gbc.gridx = 1;
        lblTransferDirection = new JLabel("Không hoạt động");
        card.add(lblTransferDirection, gbc);

        // Tốc độ & ETA
        gbc.gridx = 0;
        gbc.gridy = 3;
        card.add(new JLabel("Tốc độ mạng:"), gbc);
        gbc.gridx = 1;
        JPanel speedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        lblSpeed = new JLabel("0.0 KB/s");
        lblSpeed.setFont(lblSpeed.getFont().deriveFont(Font.BOLD));
        lblEta = new JLabel("Thời gian còn lại: --:--");
        speedRow.add(lblSpeed);
        speedRow.add(new JLabel("|"));
        speedRow.add(lblEta);
        card.add(speedRow, gbc);

        // Trạng thái
        gbc.gridx = 0;
        gbc.gridy = 4;
        card.add(new JLabel("Trạng thái:"), gbc);
        gbc.gridx = 1;
        lblState = new JLabel("SẴN SÀNG (CHỜ TỆP TIN)");
        lblState.setFont(lblState.getFont().deriveFont(Font.BOLD));
        card.add(lblState, gbc);

        // Lỗi nếu có
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        lblError = new JLabel("");
        lblError.setForeground(new Color(229, 87, 83));
        lblError.setFont(lblError.getFont().deriveFont(Font.BOLD, 12f));
        card.add(lblError, gbc);

        // Progress Bar
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("0%");
        progressBar.setPreferredSize(new Dimension(Integer.MAX_VALUE, 26));
        card.add(progressBar, gbc);

        // Điều khiển (Nút bấm)
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        btnCancel = new JButton("Hủy truyền");
        btnCancel.setEnabled(false); // Disabled khi chưa có backend thật
        btnResume = new JButton("Tiếp tục (Resume)");
        btnResume.setEnabled(false); // Disabled khi chưa có backend M4
        btnOpenFolder = new JButton("Mở thư mục đích");
        btnOpenFolder.setEnabled(false);

        actionsRow.add(btnCancel);
        actionsRow.add(btnResume);
        actionsRow.add(btnOpenFolder);
        card.add(actionsRow, gbc);

        container.add(card);
        container.add(Box.createVerticalGlue());

        add(container, BorderLayout.NORTH);
    }

    /**
     * Cập nhật toàn bộ giao diện tiến trình dựa trên ViewModel nhận từ Presenter/Adapter.
     */
    public void updateTransferProgress(TransferViewModel transfer) {
        if (transfer == null || transfer.state() == TransferViewModel.TransferState.IDLE) {
            resetToIdle();
            return;
        }

        this.currentTransfer = transfer;
        lblFileName.setText(transfer.fileName());
        lblFileSize.setText(transfer.getFormattedSizeProgress());
        lblTransferDirection.setText(transfer.direction().getDisplayName());
        lblSpeed.setText(transfer.getFormattedSpeed());
        lblEta.setText("Thời gian còn lại: " + transfer.getFormattedEta());
        lblState.setText(transfer.state().getDisplayName().toUpperCase());

        int pct = transfer.getProgressPercentage();
        progressBar.setValue(pct);
        progressBar.setString(pct + "%");

        if (transfer.errorMessage() != null && !transfer.errorMessage().isBlank()) {
            lblError.setText("Lỗi: " + transfer.errorMessage());
        } else {
            lblError.setText("");
        }

        // Cập nhật trạng thái các nút theo trạng thái truyền
        boolean isRunning = transfer.state() == TransferViewModel.TransferState.TRANSFERRING;
        boolean isCompleted = transfer.state() == TransferViewModel.TransferState.COMPLETED;
        boolean isFailed = transfer.state() == TransferViewModel.TransferState.FAILED;

        // Lưu ý: Nút cancel/resume chỉ bật khi có phiên thật ở UI-03; ở UI-02 giữ disabled theo yêu cầu
        btnCancel.setEnabled(false);
        btnResume.setEnabled(false);
        btnOpenFolder.setEnabled(isCompleted);
    }

    /**
     * Đặt lại giao diện về trạng thái sẵn sàng ban đầu.
     */
    public void resetToIdle() {
        this.currentTransfer = TransferViewModel.createIdle();
        lblFileName.setText("(Chưa có tệp tin đang truyền)");
        lblFileSize.setText("0 B / 0 B");
        lblTransferDirection.setText("Không hoạt động");
        lblSpeed.setText("0.0 KB/s");
        lblEta.setText("Thời gian còn lại: --:--");
        lblState.setText("SẴN SÀNG (CHỜ TỆP TIN)");
        lblError.setText("");
        progressBar.setValue(0);
        progressBar.setString("0%");
        btnCancel.setEnabled(false);
        btnResume.setEnabled(false);
        btnOpenFolder.setEnabled(false);
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

    public JButton getBtnCancel() {
        return btnCancel;
    }

    public JButton getBtnResume() {
        return btnResume;
    }

    public JButton getBtnOpenFolder() {
        return btnOpenFolder;
    }

    public JProgressBar getProgressBar() {
        return progressBar;
    }

    public TransferViewModel getCurrentTransfer() {
        return currentTransfer;
    }
}

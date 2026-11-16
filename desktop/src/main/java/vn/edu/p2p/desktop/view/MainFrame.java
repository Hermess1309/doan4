package vn.edu.p2p.desktop.view;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import vn.edu.p2p.core.FileOffer;
import vn.edu.p2p.desktop.adapter.CoreApiAdapter;
import vn.edu.p2p.desktop.adapter.DummyCoreApiAdapter;
import vn.edu.p2p.desktop.adapter.RealCoreApiAdapter;
import vn.edu.p2p.desktop.model.AppSettings;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.util.Formatters;
import vn.edu.p2p.desktop.util.ValidationUtils;
import vn.edu.p2p.desktop.view.screens.DashboardPanel;
import vn.edu.p2p.desktop.view.screens.DevicesPanel;
import vn.edu.p2p.desktop.view.screens.SecurityPanel;
import vn.edu.p2p.desktop.view.screens.SettingsPanel;
import vn.edu.p2p.desktop.view.screens.TransfersPanel;

/**
 * Cửa sổ chính của ứng dụng P2P File Transfer Desktop.
 * Sử dụng BorderLayout kết hợp CardLayout để điều hướng mượt mà giữa các màn hình.
 */
public class MainFrame extends JFrame {

    private final CoreApiAdapter adapter;
    private final CardLayout cardLayout;
    private final JPanel cardsPanel;
    private final SidebarPanel sidebarPanel;
    private final HeaderPanel headerPanel;
    private final StatusBarPanel statusBarPanel;

    private final DashboardPanel dashboardPanel;
    private final DevicesPanel devicesPanel;
    private final TransfersPanel transfersPanel;
    private final SecurityPanel securityPanel;
    private final SettingsPanel settingsPanel;

    private SessionViewModel currentSession = SessionViewModel.createDisconnected();

    public MainFrame() {
        this(new DummyCoreApiAdapter());
    }

    public MainFrame(CoreApiAdapter adapter) {
        super("P2P File Transfer — Secure End-to-End (Java 21 & FlatLaf)");
        this.adapter = (adapter != null) ? adapter : new DummyCoreApiAdapter();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 640);
        setMinimumSize(new Dimension(820, 540));
        setLocationRelativeTo(null);

        // Khởi tạo các panel chính
        sidebarPanel = new SidebarPanel();
        headerPanel = new HeaderPanel();
        statusBarPanel = new StatusBarPanel();

        // Khởi tạo các màn hình con
        dashboardPanel = new DashboardPanel();
        devicesPanel = new DevicesPanel();
        transfersPanel = new TransfersPanel();
        securityPanel = new SecurityPanel();
        settingsPanel = new SettingsPanel();

        // Cập nhật cấu hình ban đầu từ adapter
        securityPanel.setLocalFingerprint(this.adapter.getLocalFingerprint());
        dashboardPanel.setListeningPort(this.adapter.getListenPort());

        // Lắng nghe sự kiện từ DashboardPanel
        dashboardPanel.setOnSendRequested(file -> {
            if (currentSession != null && currentSession.isReadyToTransfer()) {
                this.adapter.offerFile(currentSession.sessionId(), file);
                navigateToTab(SidebarPanel.TAB_TRANSFERS);
            }
        });

        // Lắng nghe sự kiện từ DevicesPanel
        devicesPanel.setOnConnectLanRequested((host, port) -> {
            this.adapter.connectDirect(host, port);
        });
        devicesPanel.setOnDisconnectRequested(() -> {
            if (currentSession != null && !currentSession.sessionId().isBlank()) {
                this.adapter.disconnect(currentSession.sessionId());
            }
        });

        // Lắng nghe sự kiện từ SecurityPanel
        securityPanel.setOnTlsHandshakeRequested((sessionId, pin) -> {
            this.adapter.startTlsHandshake(sessionId, pin);
        });

        // Lắng nghe sự kiện từ TransfersPanel
        transfersPanel.setOnCancelRequested(transferId -> {
            this.adapter.cancelTransfer(transferId);
        });

        // Đăng ký nhận cập nhật từ CoreApiAdapter
        this.adapter.registerListeners(
                sessionVm -> SwingUtilities.invokeLater(() -> updateSession(sessionVm)),
                transferVm -> SwingUtilities.invokeLater(() -> updateTransfer(transferVm))
        );

        // Đăng ký bộ xử lý nhận đề nghị gửi tệp nếu là RealCoreApiAdapter
        if (this.adapter instanceof RealCoreApiAdapter realAdapter) {
            realAdapter.setIncomingOfferHandler(this::promptIncomingOfferDialog);
        }

        // Đóng adapter khi thoát ứng dụng
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                MainFrame.this.adapter.shutdown();
            }
        });

        // Vùng chứa CardLayout
        cardLayout = new CardLayout();
        cardsPanel = new JPanel(cardLayout);
        cardsPanel.add(dashboardPanel, SidebarPanel.TAB_DASHBOARD);
        cardsPanel.add(devicesPanel, SidebarPanel.TAB_DEVICES);
        cardsPanel.add(transfersPanel, SidebarPanel.TAB_TRANSFERS);
        cardsPanel.add(securityPanel, SidebarPanel.TAB_SECURITY);
        cardsPanel.add(settingsPanel, SidebarPanel.TAB_SETTINGS);

        // Bố cục trung tâm (Header ở trên, Cards ở giữa)
        JPanel centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(headerPanel, BorderLayout.NORTH);
        centerContainer.add(cardsPanel, BorderLayout.CENTER);

        // Bố cục tổng thể của Frame
        setLayout(new BorderLayout());
        add(sidebarPanel, BorderLayout.WEST);
        add(centerContainer, BorderLayout.CENTER);
        add(statusBarPanel, BorderLayout.SOUTH);

        // Lắng nghe sự kiện chuyển tab từ Sidebar
        sidebarPanel.setOnNavigate(this::navigateToTab);
    }

    private void promptIncomingOfferDialog(FileOffer offer, Consumer<File> decisionCallback) {
        String message = String.format("""
                Thiết bị đối tác gửi yêu cầu truyền tệp tin:
                - Tên tệp: %s
                - Dung lượng: %s
                - Băm SHA-256: %s

                Bạn có muốn tiếp nhận tệp tin này không?
                """,
                offer.fileName(),
                Formatters.formatBytes(offer.size()),
                offer.sha256());

        int choice = JOptionPane.showConfirmDialog(
                this,
                message,
                "Yêu cầu truyền tệp tin đến",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (choice == JOptionPane.YES_OPTION) {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(AppSettings.getDownloadDirectory());
            chooser.setDialogTitle("Chọn vị trí lưu tệp tin (Không được ghi đè)");
            chooser.setSelectedFile(new File(AppSettings.getDownloadDirectory(), ValidationUtils.sanitizeFileName(offer.fileName())));

            int saveChoice = chooser.showSaveDialog(this);
            if (saveChoice == javax.swing.JFileChooser.APPROVE_OPTION) {
                File targetFile = chooser.getSelectedFile();
                if (targetFile.exists()) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Tệp tin đã tồn tại trên đĩa! Theo chính sách bảo mật M3, không được ghi đè.",
                            "Cảnh báo trùng lặp tệp",
                            JOptionPane.WARNING_MESSAGE
                    );
                    decisionCallback.accept(null);
                    return;
                }
                decisionCallback.accept(targetFile);
                navigateToTab(SidebarPanel.TAB_TRANSFERS);
                return;
            }
        }
        decisionCallback.accept(null);
    }

    public void navigateToTab(String tabKey) {
        cardLayout.show(cardsPanel, tabKey);
        sidebarPanel.setSelectedTab(tabKey);
        switch (tabKey) {
            case SidebarPanel.TAB_DASHBOARD -> headerPanel.setTitle("Tổng quan & Gửi tệp tin");
            case SidebarPanel.TAB_DEVICES -> headerPanel.setTitle("Quản lý Thiết bị & Kết nối");
            case SidebarPanel.TAB_TRANSFERS -> headerPanel.setTitle("Tiến trình truyền tải tệp tin");
            case SidebarPanel.TAB_SECURITY -> headerPanel.setTitle("Bảo mật & Xác thực Vân tay");
            case SidebarPanel.TAB_SETTINGS -> headerPanel.setTitle("Cài đặt hệ thống");
            default -> headerPanel.setTitle("P2P File Transfer");
        }
    }

    /**
     * Cập nhật toàn bộ trạng thái phiên kết nối trên các màn hình và thanh trạng thái.
     * Ánh xạ chính xác theo các trạng thái an toàn của M2: TCP_CONNECTED, WAITING_FOR_FINGERPRINT,
     * TLS_HANDSHAKING, SECURE_READY.
     */
    public void updateSession(SessionViewModel session) {
        this.currentSession = (session != null) ? session : SessionViewModel.createDisconnected();
        dashboardPanel.updateSessionState(currentSession);
        devicesPanel.updateSession(currentSession);
        securityPanel.updateSession(currentSession);

        if (session == null || session.sessionId().isBlank() || session.state() == SessionState.DISCONNECTED) {
            headerPanel.setConnectionBadge("[ Chưa kết nối ]", new Color(157, 160, 168));
            statusBarPanel.setEngineStatus("Sẵn sàng (Chưa kết nối)");
            return;
        }

        switch (session.state()) {
            case TCP_CONNECTED -> {
                headerPanel.setConnectionBadge("[ TCP: " + session.remoteAddress() + " (Chưa TLS) ]", new Color(229, 168, 59));
                statusBarPanel.setEngineStatus("Đã nối TCP — Chờ nhập vân tay trước khi bắt tay TLS");
            }
            case WAITING_FOR_FINGERPRINT -> {
                headerPanel.setConnectionBadge("[ Chờ nhập vân tay ]", new Color(229, 168, 59));
                statusBarPanel.setEngineStatus("Chờ nhập vân tay đối tác từ kênh độc lập");
            }
            case TLS_HANDSHAKING -> {
                headerPanel.setConnectionBadge("[ ⟳ Đang bắt tay TLS 1.3... ]", new Color(53, 116, 240));
                statusBarPanel.setEngineStatus("Đang tiến hành bắt tay Mutual TLS 1.3...");
            }
            case SECURE_READY -> {
                headerPanel.setConnectionBadge("[ ✓ Bảo mật TLS 1.3: " + session.remoteAddress() + " ]", new Color(73, 156, 84));
                statusBarPanel.setEngineStatus("Kết nối an toàn đã xác thực (Mutual TLS 1.3)");
            }
            case ERROR -> {
                headerPanel.setConnectionBadge("[ ✕ Lỗi kết nối / TLS ]", new Color(229, 87, 83));
                statusBarPanel.setEngineStatus("Lỗi: " + session.errorMessage());
            }
            default -> {
                headerPanel.setConnectionBadge("[ " + session.state().getDisplayName() + " ]", new Color(157, 160, 168));
                statusBarPanel.setEngineStatus(session.state().getDisplayName());
            }
        }
    }

    /**
     * Cập nhật tiến trình truyền tải file trên Dashboard và TransfersPanel.
     */
    public void updateTransfer(TransferViewModel transfer) {
        dashboardPanel.updateTransferProgress(transfer);
        transfersPanel.updateTransferProgress(transfer);
        if (transfer != null) {
            statusBarPanel.setNetworkSpeed("Tốc độ: " + transfer.getFormattedSpeed());
        }
    }

    public DashboardPanel getDashboardPanel() {
        return dashboardPanel;
    }

    public DevicesPanel getDevicesPanel() {
        return devicesPanel;
    }

    public TransfersPanel getTransfersPanel() {
        return transfersPanel;
    }

    public SecurityPanel getSecurityPanel() {
        return securityPanel;
    }

    public SettingsPanel getSettingsPanel() {
        return settingsPanel;
    }

    public HeaderPanel getHeaderPanel() {
        return headerPanel;
    }

    public StatusBarPanel getStatusBarPanel() {
        return statusBarPanel;
    }
}

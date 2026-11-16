package vn.edu.p2p.desktop.view;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.JPanel;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel;
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

    public MainFrame() {
        super("P2P File Transfer — Secure End-to-End (Java 21 & FlatLaf)");
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
     */
    public void updateSession(SessionViewModel session) {
        dashboardPanel.updateSessionState(session);
        devicesPanel.updateSession(session);
        securityPanel.updateSession(session);

        if (session == null || session.sessionId().isBlank()) {
            headerPanel.setConnectionBadge("[ Chưa kết nối ]", new Color(157, 160, 168));
            statusBarPanel.setEngineStatus("Sẵn sàng (Chưa kết nối)");
        } else if (session.isFingerprintVerified()) {
            headerPanel.setConnectionBadge("[ ✓ " + session.remoteAddress() + " ]", new Color(73, 156, 84));
            statusBarPanel.setEngineStatus("Đã kết nối & Xác thực an toàn");
        } else {
            headerPanel.setConnectionBadge("[ ! " + session.remoteAddress() + " (Chưa xác minh) ]", new Color(229, 168, 59));
            statusBarPanel.setEngineStatus("Đã kết nối TLS - Chờ xác minh vân tay");
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

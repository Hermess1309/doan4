package vn.edu.p2p.desktop.view;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.File;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public enum BackendState {
        INITIALIZING,
        READY,
        FAILED
    }

    private final boolean productionMode;
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

    private final AtomicBoolean resourcesClosed = new AtomicBoolean(false);
    private volatile CoreApiAdapter adapter;
    private volatile BackendState backendState;
    private volatile String initializationError = "";
    private volatile Runnable onShutdownHook;
    private SessionViewModel currentSession = SessionViewModel.createDisconnected();
    private TransferViewModel currentTransfer = TransferViewModel.createIdle();
    private long activeSessionGeneration = 0L;
    private String activeSessionId = "";

    /**
     * Constructor mặc định chỉ dành riêng cho unit test, preview UI và chế độ --smoke-test.
     * Chế độ ứng dụng thật bắt buộc dùng {@link #createProductionWindow()} hoặc
     * {@link #MainFrame(CoreApiAdapter)} với adapter thật.
     */
    public MainFrame() {
        this(new DummyCoreApiAdapter(), false);
    }

    /**
     * Khởi tạo cửa sổ gắn trực tiếp một {@link CoreApiAdapter} đã sẵn sàng.
     * Tuyệt đối không tự fallback sang {@link DummyCoreApiAdapter} khi adapter null.
     */
    public MainFrame(CoreApiAdapter adapter) {
        this(Objects.requireNonNull(adapter, "adapter không được null"), false);
    }

    /**
     * Khởi tạo cửa sổ chế độ production với backend được chuẩn bị bất đồng bộ ngoài EDT.
     * Hiển thị trạng thái "Đang khởi tạo ứng dụng..." và khóa toàn bộ chức năng mạng
     * cho đến khi backend thật sẵn sàng.
     */
    public static MainFrame createProductionWindow() {
        return new MainFrame(null, true);
    }

    private MainFrame(CoreApiAdapter initialAdapter, boolean productionMode) {
        super("P2P File Transfer — Secure End-to-End (Java 21 & FlatLaf)");
        this.productionMode = productionMode;

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

        // Gắn các hành động giao diện (luôn kiểm tra backend READY và adapter != null)
        dashboardPanel.setOnSendRequested(
                file -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY
                            && currentAdapter != null
                            && currentSession != null
                            && currentSession.isReadyToTransfer()) {
                        currentAdapter.offerFile(currentSession.sessionId(), file);
                        navigateToTab(SidebarPanel.TAB_TRANSFERS);
                    }
                });

        devicesPanel.setOnConnectLanRequested(
                (host, port) -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY && currentAdapter != null) {
                        currentAdapter.connectDirect(host, port);
                    }
                });
        devicesPanel.setOnCreateRelayRequested(
                (host, port) -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY && currentAdapter != null) {
                        currentAdapter
                                .createRelaySession(host, port)
                                .thenAccept(
                                        ticket ->
                                                SwingUtilities.invokeLater(
                                                        () -> {
                                                            if (!resourcesClosed.get()
                                                                    && backendState == BackendState.READY
                                                                    && ticket != null
                                                                    && ticket.sessionGeneration()
                                                                            >= activeSessionGeneration) {
                                                                devicesPanel.displayGeneratedRelayTicket(ticket);
                                                            }
                                                        }))
                                .exceptionally(err -> null);
                    }
                });
        devicesPanel.setOnJoinRelayRequested(
                (host, port, pairingCode) -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY && currentAdapter != null) {
                        currentAdapter.joinRelaySession(host, port, pairingCode);
                    }
                });
        devicesPanel.setOnDisconnectRequested(
                () -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY && currentAdapter != null) {
                        String sId = (currentSession != null) ? currentSession.sessionId() : "";
                        currentAdapter.disconnect(sId);
                    }
                });

        securityPanel.setOnTlsHandshakeRequested(
                (sessionId, pin) -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY && currentAdapter != null) {
                        currentAdapter.startTlsHandshake(sessionId, pin);
                    }
                });

        transfersPanel.setOnCancelRequested(
                transferId -> {
                    CoreApiAdapter currentAdapter = this.adapter;
                    if (backendState == BackendState.READY && currentAdapter != null) {
                        currentAdapter.cancelTransfer(transferId);
                    }
                });

        // Đóng toàn bộ tài nguyên backend và background worker khi người dùng đóng cửa sổ
        addWindowListener(
                new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        shutdownResources();
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

        if (initialAdapter != null) {
            attachBackendAdapter(initialAdapter);
        } else {
            enterInitializingState();
        }
    }

    private void enterInitializingState() {
        this.adapter = null;
        this.backendState = BackendState.INITIALIZING;
        this.initializationError = "";

        String initText = "Đang khởi tạo ứng dụng...";
        headerPanel.setConnectionBadge("[ ⟳ " + initText + " ]", new Color(53, 116, 240));
        statusBarPanel.setEngineStatus(initText);
        statusBarPanel.setLocalInfo("LAN Port: Đang khởi tạo...");
        dashboardPanel.setBackendInitializing(initText);
        devicesPanel.setNetworkEnabled(false, initText, false);
        securityPanel.setBackendInitializing(initText);
    }

    /**
     * Gắn backend thật sau khi tác vụ khởi tạo nền hoàn tất. Bắt buộc gọi trên Swing EDT.
     */
    public void attachBackendAdapter(CoreApiAdapter realAdapter) {
        Objects.requireNonNull(realAdapter, "realAdapter không được null");
        if (productionMode && realAdapter instanceof DummyCoreApiAdapter) {
            throw new IllegalArgumentException(
                    "Không được phép gắn DummyCoreApiAdapter vào cửa sổ chế độ production.");
        }
        if (resourcesClosed.get()) {
            realAdapter.shutdown();
            return;
        }

        this.adapter = realAdapter;
        this.backendState = BackendState.READY;
        this.initializationError = "";

        int listenPort = realAdapter.getListenPort();
        String localFingerprint = realAdapter.getLocalFingerprint();

        securityPanel.setBackendReady(localFingerprint);
        dashboardPanel.setBackendReady(listenPort);
        statusBarPanel.setLocalInfo(
                listenPort > 0 ? ("LAN Port: " + listenPort) : "LAN Port: Chưa mở");
        devicesPanel.setNetworkEnabled(true, "", false);

        realAdapter.registerListeners(
                sessionVm -> {
                    if (SwingUtilities.isEventDispatchThread()) {
                        updateSession(sessionVm);
                    } else {
                        SwingUtilities.invokeLater(() -> updateSession(sessionVm));
                    }
                },
                transferVm -> {
                    if (SwingUtilities.isEventDispatchThread()) {
                        updateTransfer(transferVm);
                    } else {
                        SwingUtilities.invokeLater(() -> updateTransfer(transferVm));
                    }
                });

        if (realAdapter instanceof RealCoreApiAdapter coreAdapter) {
            coreAdapter.setIncomingOfferHandler(this::promptIncomingOfferDialog);
        }

        updateSession(currentSession);
    }

    /**
     * Đánh dấu khởi tạo backend thất bại trong chế độ production:
     * tuyệt đối không fallback sang Dummy, khóa toàn bộ chức năng mạng và hiển thị lỗi rõ ràng.
     */
    public void markBackendInitializationFailed(Throwable error) {
        this.adapter = null;
        this.backendState = BackendState.FAILED;
        String detail =
                (error != null && error.getMessage() != null && !error.getMessage().isBlank())
                        ? error.getMessage()
                        : "Không thể khởi tạo danh tính hoặc dịch vụ mạng P2P.";
        this.initializationError = detail;

        headerPanel.setConnectionBadge("[ ✕ Lỗi khởi tạo hệ thống ]", new Color(229, 87, 83));
        statusBarPanel.setEngineStatus("Lỗi khởi tạo: " + detail);
        statusBarPanel.setLocalInfo("LAN Port: Không khả dụng");
        dashboardPanel.setBackendFailed(detail);
        devicesPanel.setNetworkEnabled(false, detail, true);
        securityPanel.setBackendFailed(detail);
        transfersPanel.resetToIdle();
    }

    public void setOnShutdownHook(Runnable onShutdownHook) {
        this.onShutdownHook = onShutdownHook;
    }

    public void shutdownResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        Runnable hook = this.onShutdownHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (Exception ignored) {
                // Không để lỗi shutdown làm treo tiến trình đóng cửa sổ
            }
        }
        CoreApiAdapter currentAdapter = this.adapter;
        if (currentAdapter != null) {
            try {
                currentAdapter.shutdown();
            } catch (Exception ignored) {
                // Giải phóng best-effort khi đóng
            }
        }
    }

    @Override
    public void dispose() {
        shutdownResources();
        super.dispose();
    }

    private void promptIncomingOfferDialog(FileOffer offer, Consumer<File> decisionCallback) {
        if (resourcesClosed.get() || backendState != BackendState.READY) {
            decisionCallback.accept(null);
            return;
        }
        String message =
                String.format(
                        """
                Thiết bị đối tác gửi yêu cầu truyền tệp tin:
                - Tên tệp: %s
                - Dung lượng: %s
                - Băm SHA-256: %s

                Bạn có muốn tiếp nhận tệp tin này không?
                """,
                        offer.fileName(), Formatters.formatBytes(offer.size()), offer.sha256());

        int choice =
                JOptionPane.showConfirmDialog(
                        this,
                        message,
                        "Yêu cầu truyền tệp tin đến",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(AppSettings.getDownloadDirectory());
            chooser.setDialogTitle("Chọn vị trí lưu tệp tin (Không được ghi đè)");
            chooser.setSelectedFile(
                    new File(
                            AppSettings.getDownloadDirectory(),
                            ValidationUtils.sanitizeFileName(offer.fileName())));

            int saveChoice = chooser.showSaveDialog(this);
            if (saveChoice == javax.swing.JFileChooser.APPROVE_OPTION) {
                File targetFile = chooser.getSelectedFile();
                if (targetFile.exists()) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Tệp tin đã tồn tại trên đĩa! Theo chính sách bảo mật M3, không được ghi đè.",
                            "Cảnh báo trùng lặp tệp",
                            JOptionPane.WARNING_MESSAGE);
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
     * Kiểm tra sessionGeneration và sessionId ngay tại thời điểm thực thi trên Swing EDT
     * để loại bỏ tuyệt đối mọi callback đến muộn từ phiên cũ hoặc sau khi đóng cửa sổ.
     */
    public void updateSession(SessionViewModel session) {
        if (resourcesClosed.get() || backendState != BackendState.READY) {
            return;
        }
        SessionViewModel candidate =
                (session != null) ? session : SessionViewModel.createDisconnected(activeSessionGeneration);

        long candidateGen = candidate.sessionGeneration();
        if (candidateGen > 0) {
            if (candidateGen < activeSessionGeneration) {
                // Bỏ qua callback đến muộn từ phiên thế hệ cũ
                return;
            }
            if (candidateGen == activeSessionGeneration) {
                if ((currentSession.state() == SessionState.DISCONNECTED
                                || currentSession.state() == SessionState.CLOSED)
                        && candidate.state() != SessionState.DISCONNECTED
                        && candidate.state() != SessionState.CLOSED) {
                    return;
                }
                if (candidate.state() == SessionState.SECURE_READY
                        && !activeSessionId.isBlank()
                        && !activeSessionId.equals(candidate.sessionId())) {
                    return;
                }
            } else {
                // Chuyển sang thế hệ phiên mới: đặt lại tiến trình truyền cũ để không rò rỉ sang phiên mới
                activeSessionGeneration = candidateGen;
                activeSessionId = (candidate.sessionId() != null) ? candidate.sessionId() : "";
                TransferViewModel idle = TransferViewModel.createIdle(candidateGen);
                this.currentTransfer = idle;
                dashboardPanel.updateTransferProgress(idle);
                transfersPanel.resetToIdle();
            }

            if (candidate.state() == SessionState.DISCONNECTED
                    || candidate.state() == SessionState.CLOSED) {
                activeSessionId = "";
            } else if (candidate.sessionId() != null && !candidate.sessionId().isBlank()) {
                activeSessionId = candidate.sessionId();
            }
        } else if (activeSessionGeneration > 0) {
            // Đã kích hoạt theo dõi generation: bỏ qua callback không mang generation hợp lệ
            return;
        }

        this.currentSession = candidate;
        dashboardPanel.updateSessionState(currentSession);
        devicesPanel.updateSession(currentSession);
        securityPanel.updateSession(currentSession);

        if (currentSession.state() == SessionState.ERROR) {
            headerPanel.setConnectionBadge("[ ✕ Lỗi kết nối / TLS ]", new Color(229, 87, 83));
            String err =
                    (currentSession.errorMessage() != null && !currentSession.errorMessage().isBlank())
                            ? currentSession.errorMessage()
                            : "Lỗi kết nối / xác thực TLS";
            statusBarPanel.setEngineStatus("Lỗi: " + err);
            return;
        }

        if (currentSession.state() == SessionState.RELAY_CONNECTING) {
            headerPanel.setConnectionBadge("[ ⟳ Đang kết nối Relay... ]", new Color(53, 116, 240));
            statusBarPanel.setEngineStatus("Đang kết nối tới Relay Server...");
            return;
        }

        if (currentSession.state() == SessionState.WAITING_FOR_PEER) {
            headerPanel.setConnectionBadge("[ ⟳ Relay: Chờ đối tác ]", new Color(229, 168, 59));
            statusBarPanel.setEngineStatus("Đang chờ đối tác ghép nối qua Relay (Chưa có TLS)");
            return;
        }

        if (currentSession.state() == SessionState.TCP_CONNECTED) {
            headerPanel.setConnectionBadge(
                    "[ TCP: " + currentSession.remoteAddress() + " (Chưa TLS) ]",
                    new Color(229, 168, 59));
            statusBarPanel.setEngineStatus("Đã ghép nối TCP — Chờ nhập vân tay trước khi bắt tay TLS");
            return;
        }

        if (currentSession.sessionId().isBlank()
                || currentSession.state() == SessionState.DISCONNECTED) {
            headerPanel.setConnectionBadge("[ Chưa kết nối ]", new Color(157, 160, 168));
            statusBarPanel.setEngineStatus("Sẵn sàng (Chưa kết nối)");
            return;
        }

        switch (currentSession.state()) {
            case WAITING_FOR_FINGERPRINT -> {
                String channel = currentSession.isRelay() ? "Relay" : "LAN";
                headerPanel.setConnectionBadge(
                        "[ " + channel + ": Chờ nhập vân tay ]", new Color(229, 168, 59));
                statusBarPanel.setEngineStatus(
                        "Đã ghép nối TCP (" + channel + ") — Chờ nhập vân tay đối tác từ kênh độc lập");
            }
            case TLS_HANDSHAKING -> {
                headerPanel.setConnectionBadge("[ ⟳ Đang bắt tay TLS 1.3... ]", new Color(53, 116, 240));
                statusBarPanel.setEngineStatus("Đang tiến hành bắt tay Mutual TLS 1.3...");
            }
            case SECURE_READY -> {
                headerPanel.setConnectionBadge(
                        "[ ✓ Bảo mật TLS 1.3: " + currentSession.remoteAddress() + " ]",
                        new Color(73, 156, 84));
                statusBarPanel.setEngineStatus("Kết nối an toàn đã xác thực (Mutual TLS 1.3)");
            }
            default -> {
                headerPanel.setConnectionBadge(
                        "[ " + currentSession.state().getDisplayName() + " ]", new Color(157, 160, 168));
                statusBarPanel.setEngineStatus(currentSession.state().getDisplayName());
            }
        }
    }

    /**
     * Cập nhật tiến trình truyền tải file trên Dashboard và TransfersPanel.
     * Bỏ qua callback transfer của thế hệ/phiên cũ hoặc sau khi đóng ứng dụng.
     */
    public void updateTransfer(TransferViewModel transfer) {
        if (resourcesClosed.get() || backendState != BackendState.READY) {
            return;
        }
        if (transfer != null) {
            long transferGen = transfer.sessionGeneration();
            if (transferGen > 0) {
                if (transferGen < activeSessionGeneration) {
                    return;
                }
                if (!activeSessionId.isBlank()
                        && transfer.sessionId() != null
                        && !transfer.sessionId().isBlank()
                        && !activeSessionId.equals(transfer.sessionId())) {
                    return;
                }
            } else if (activeSessionGeneration > 0
                    && transfer.state() != TransferViewModel.TransferState.IDLE) {
                return;
            }
        }

        this.currentTransfer = (transfer != null) ? transfer : TransferViewModel.createIdle();
        dashboardPanel.updateTransferProgress(currentTransfer);
        transfersPanel.updateTransferProgress(currentTransfer);
        statusBarPanel.setNetworkSpeed("Tốc độ: " + currentTransfer.getFormattedSpeed());
    }

    public SessionViewModel getCurrentSession() {
        return currentSession;
    }

    public TransferViewModel getCurrentTransfer() {
        return currentTransfer;
    }

    public BackendState getBackendState() {
        return backendState;
    }

    public CoreApiAdapter getAttachedAdapter() {
        return adapter;
    }

    public String getInitializationError() {
        return initializationError;
    }

    public boolean isResourcesClosed() {
        return resourcesClosed.get();
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

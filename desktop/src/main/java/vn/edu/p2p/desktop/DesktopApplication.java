package vn.edu.p2p.desktop;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import vn.edu.p2p.desktop.adapter.CoreApiAdapter;
import vn.edu.p2p.desktop.adapter.RealCoreApiAdapter;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;

/**
 * Điểm khởi chạy chính của ứng dụng P2P File Transfer Desktop.
 * Đảm bảo mọi tác vụ giao diện chạy trên Swing Event Dispatch Thread (EDT) và
 * mọi tác vụ khởi tạo danh tính/keytool/mạng chạy trên background worker ngoài EDT.
 */
public final class DesktopApplication {

    private DesktopApplication() {}

    record StartupHandle(
            MainFrame frame, CompletableFuture<Void> initFuture, ExecutorService startupExecutor) {}

    public static void main(String[] args) {
        // 1. Cài đặt FlatLaf Look and Feel trước khi vẽ các component Swing
        ThemeManager.initialize();

        // 2. Phân tích các cờ dòng lệnh không nhạy cảm (--name, --port, --store).
        //    Tuyệt đối không nhận mật khẩu keystore từ tham số dòng lệnh.
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--name=")) {
                    System.setProperty("p2p.identity.name", arg.substring("--name=".length()).trim());
                } else if (arg.startsWith("--port=")) {
                    System.setProperty("p2p.listen.port", arg.substring("--port=".length()).trim());
                } else if (arg.startsWith("--store=")) {
                    System.setProperty("p2p.identity.store", arg.substring("--store=".length()).trim());
                }
            }
        }

        // 3. Chế độ kiểm thử smoke-test / CI được lựa chọn rõ ràng: không khởi động mạng thật
        if (isSmokeCheckRequested(args)) {
            int exitCode = executeCiSmokeCheck();
            if (exitCode != 0) {
                System.err.println(
                        "P2P File Transfer Desktop: Kiểm tra khởi tạo trong CI THẤT BẠI (exitCode="
                                + exitCode
                                + ").");
                System.exit(exitCode);
            }
            return;
        }

        // 4. Chế độ production: dựng cửa sổ ngay trên EDT và khởi tạo Core trên background worker
        SwingUtilities.invokeLater(
                () -> startProductionOnEdt(RealCoreApiAdapter::createDefault, true, true, null));
    }

    /**
     * Khởi chạy giao diện chế độ production trên EDT với backend được chuẩn bị bất đồng bộ.
     * Tuyệt đối không fallback sang DummyCoreApiAdapter khi khởi tạo thất bại.
     */
    static StartupHandle startProductionOnEdt(
            Callable<CoreApiAdapter> adapterSupplier,
            boolean showWindow,
            boolean showErrorDialog,
            Consumer<StartupHandle> onHandleReady) {
        Objects.requireNonNull(adapterSupplier, "adapterSupplier không được null");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("startProductionOnEdt phải được gọi trên Swing EDT.");
        }

        MainFrame mainFrame = MainFrame.createProductionWindow();
        ExecutorService startupExecutor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "p2p-desktop-startup");
                            thread.setDaemon(true);
                            return thread;
                        });
        CompletableFuture<Void> initFuture = new CompletableFuture<>();
        StartupHandle handle = new StartupHandle(mainFrame, initFuture, startupExecutor);

        mainFrame.setOnShutdownHook(
                () -> {
                    initFuture.cancel(true);
                    startupExecutor.shutdownNow();
                });

        if (showWindow) {
            mainFrame.setVisible(true);
        }
        if (onHandleReady != null) {
            onHandleReady.accept(handle);
        }

        startupExecutor.submit(
                () -> {
                    try {
                        CoreApiAdapter realAdapter = adapterSupplier.call();
                        if (realAdapter == null) {
                            throw new IllegalStateException("Backend adapter trả về null.");
                        }
                        if (mainFrame.isResourcesClosed() || Thread.currentThread().isInterrupted()) {
                            realAdapter.shutdown();
                            initFuture.complete(null);
                            return;
                        }

                        SwingUtilities.invokeLater(
                                () -> {
                                    try {
                                        if (mainFrame.isResourcesClosed()) {
                                            realAdapter.shutdown();
                                        } else {
                                            mainFrame.attachBackendAdapter(realAdapter);
                                        }
                                    } catch (Throwable attachError) {
                                        mainFrame.markBackendInitializationFailed(attachError);
                                        realAdapter.shutdown();
                                    } finally {
                                        initFuture.complete(null);
                                    }
                                });
                    } catch (Throwable initError) {
                        System.err.println(
                                "P2P File Transfer Desktop: Khởi tạo Core backend thất bại: "
                                        + initError.getMessage());
                        SwingUtilities.invokeLater(
                                () -> {
                                    try {
                                        if (!mainFrame.isResourcesClosed()) {
                                            mainFrame.markBackendInitializationFailed(initError);
                                            if (showErrorDialog) {
                                                JOptionPane.showMessageDialog(
                                                        mainFrame,
                                                        "Không thể khởi tạo danh tính bảo mật hoặc dịch vụ mạng P2P:\n"
                                                                + initError.getMessage()
                                                                + "\n\nChức năng mạng đã bị khóa an toàn để bảo vệ hệ thống.",
                                                        "Lỗi khởi tạo hệ thống",
                                                        JOptionPane.ERROR_MESSAGE);
                                            }
                                        }
                                    } finally {
                                        initFuture.complete(null);
                                    }
                                });
                    } finally {
                        startupExecutor.shutdown();
                    }
                });

        return handle;
    }

    /**
     * Xác định ứng dụng có đang được chạy ở chế độ smoke check hoặc môi trường CI hay không.
     */
    static boolean isSmokeCheckRequested(String[] args) {
        if ("true".equalsIgnoreCase(System.getenv("CI")) || Boolean.getBoolean("p2p.smoke.test")) {
            return true;
        }
        if (args != null) {
            for (String arg : args) {
                if ("--smoke-test".equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Thực hiện smoke check trong môi trường CI:
     * - Phân biệt rõ môi trường Headless vs Non-Headless.
     * - Khởi tạo MainFrame (Dummy preview adapter) trên EDT, pack() và dispose() đúng cách.
     * - Không khởi động mạng thật hoặc listener vô hạn.
     * - Trả về 0 nếu thành công, trả về != 0 nếu xảy ra lỗi (không nuốt ngoại lệ).
     */
    public static int executeCiSmokeCheck() {
        return executeCiSmokeCheck(null);
    }

    /**
     * Phục vụ kiểm thử nội bộ cơ chế bắt lỗi và giải phóng tài nguyên.
     */
    static int executeCiSmokeCheck(Consumer<MainFrame> frameValidator) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println(
                    "P2P File Transfer Desktop: Môi trường Headless (GraphicsEnvironment.isHeadless() == true).");
            System.out.println(
                    "   -> Đã kiểm tra: Nạp cấu hình Theme/Look & Feel và tính sẵn sàng của runtime.");
            System.out.println(
                    "   -> Bỏ qua: Hiển thị cửa sổ đồ họa vì không có display server (X11/Wayland).");
            return 0;
        }

        System.out.println(
                "P2P File Transfer Desktop: Phát hiện display server, tiến hành kiểm tra khởi tạo MainFrame trên EDT.");
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        MainFrame frame = null;
                        try {
                            frame = new MainFrame();
                            frame.pack();
                            if (frameValidator != null) {
                                frameValidator.accept(frame);
                            }
                        } catch (Throwable t) {
                            failureRef.compareAndSet(null, t);
                        } finally {
                            if (frame != null) {
                                try {
                                    frame.dispose();
                                } catch (Throwable disposeEx) {
                                    System.err.println(
                                            "Cảnh báo giải phóng frame trong smoke check: "
                                                    + disposeEx.getMessage());
                                }
                            }
                        }
                    });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureRef.compareAndSet(null, e);
        } catch (InvocationTargetException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            failureRef.compareAndSet(null, cause);
        } catch (Throwable t) {
            failureRef.compareAndSet(null, t);
        }

        Throwable failure = failureRef.get();
        if (failure != null) {
            System.err.println(
                    "✕ LỖI SMOKE CHECK: Khởi tạo giao diện thất bại: " + failure.getMessage());
            failure.printStackTrace(System.err);
            return 1;
        }

        System.out.println(
                "✓ P2P File Transfer Desktop: Khởi tạo và giải phóng MainFrame trên EDT thành công.");
        return 0;
    }
}

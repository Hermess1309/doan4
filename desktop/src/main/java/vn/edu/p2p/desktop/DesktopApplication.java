package vn.edu.p2p.desktop;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;

/**
 * Điểm khởi chạy chính của ứng dụng P2P File Transfer Desktop.
 * Đảm bảo mọi tác vụ khởi tạo giao diện diễn ra trên Swing Event Dispatch Thread (EDT).
 */
public final class DesktopApplication {

    private DesktopApplication() {}

    public static void main(String[] args) {
        // 1. Cài đặt FlatLaf Look and Feel trước khi vẽ các component Swing
        ThemeManager.initialize();

        // 2. Tự động đóng nếu phát hiện môi trường kiểm thử CI/không tương tác để tránh treo runner
        boolean isCi = "true".equalsIgnoreCase(System.getenv("CI"))
                || Boolean.getBoolean("p2p.smoke.test")
                || (args != null && args.length > 0 && "--smoke-test".equalsIgnoreCase(args[0]));

        if (isCi) {
            int exitCode = executeCiSmokeCheck();
            if (exitCode != 0) {
                System.err.println("P2P File Transfer Desktop: Kiểm tra khởi tạo trong CI THẤT BẠI (exitCode=" + exitCode + ").");
                System.exit(exitCode);
            }
            return;
        }

        // 3. Khởi tạo MainFrame trên Event Dispatch Thread (EDT) cho người dùng
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = new MainFrame();
            mainFrame.setVisible(true);
        });
    }

    /**
     * Thực hiện smoke check trong môi trường CI:
     * - Phân biệt rõ môi trường Headless vs Non-Headless.
     * - Khởi tạo MainFrame trên EDT, pack() và dispose() đúng cách.
     * - Trả về 0 nếu thành công, trả về != 0 nếu xảy ra lỗi (không nuốt ngoại lệ).
     */
    public static int executeCiSmokeCheck() {
        return executeCiSmokeCheck(null);
    }

    /**
     * Phục vụ kiểm thử nội bộ cơ chế bắt lỗi và giải phóng tài nguyên.
     */
    static int executeCiSmokeCheck(java.util.function.Consumer<MainFrame> frameValidator) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("P2P File Transfer Desktop: Môi trường Headless (GraphicsEnvironment.isHeadless() == true).");
            System.out.println("   -> Đã kiểm tra: Nạp cấu hình Theme/Look & Feel và tính sẵn sàng của runtime.");
            System.out.println("   -> Bỏ qua: Hiển thị cửa sổ đồ họa vì không có display server (X11/Wayland).");
            return 0;
        }

        System.out.println("P2P File Transfer Desktop: Phát hiện display server, tiến hành kiểm tra khởi tạo MainFrame trên EDT.");
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
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
                            System.err.println("Cảnh báo giải phóng frame trong smoke check: " + disposeEx.getMessage());
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
            System.err.println("✕ LỖI SMOKE CHECK: Khởi tạo giao diện thất bại: " + failure.getMessage());
            failure.printStackTrace(System.err);
            return 1;
        }

        System.out.println("✓ P2P File Transfer Desktop: Khởi tạo và giải phóng MainFrame trên EDT thành công.");
        return 0;
    }
}

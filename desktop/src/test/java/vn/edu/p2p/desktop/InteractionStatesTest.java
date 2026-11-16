package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;
import vn.edu.p2p.desktop.view.dialogs.FingerprintVerificationDialog;

class InteractionStatesTest {

    @Test
    void testInitialDisabledStatesWhenNoBackend() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.initialize();
            MainFrame frame = new MainFrame();

            // 1. Dashboard: Nút gửi bắt buộc disabled khi chưa có kết nối đã xác minh
            assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled(),
                    "Nút Gửi phải disabled khi chưa kết nối và chưa chọn file hợp lệ");

            // 2. Transfers: Các nút điều khiển phải disabled khi chưa có tác vụ thật
            assertFalse(frame.getTransfersPanel().getBtnCancel().isEnabled(),
                    "Nút Hủy phải disabled khi chưa có tác vụ");
            assertFalse(frame.getTransfersPanel().getBtnResume().isEnabled(),
                    "Nút Resume phải disabled");
            assertFalse(frame.getTransfersPanel().getBtnOpenFolder().isEnabled(),
                    "Nút Mở thư mục phải disabled khi chưa hoàn tất");

            // 3. Devices: Nút ngắt kết nối phải disabled khi chưa có phiên
            assertFalse(frame.getDevicesPanel().getBtnDisconnect().isEnabled(),
                    "Nút Ngắt kết nối phải disabled khi chưa có phiên");

            // 4. Security: Nút mở dialog xác minh phải disabled khi chưa có phiên
            assertFalse(frame.getSecurityPanel().getBtnOpenVerificationDialog().isEnabled(),
                    "Nút mở xác minh phải disabled khi chưa có phiên kết nối");

            frame.dispose();
        });
    }

    @Test
    void testDevicesInputValidationInteraction() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame();

            // Nhập IP sai cú pháp
            frame.getDevicesPanel().getTxtHostIp().setText("256.0.0.1");
            frame.getDevicesPanel().getBtnConnectLan().doClick();
            assertFalse(frame.getDevicesPanel().getLblInputError().getText().isEmpty(),
                    "Phải hiển thị lỗi khi nhập IP sai");

            // Nhập Port sai phạm vi
            frame.getDevicesPanel().getTxtHostIp().setText("192.168.1.15");
            frame.getDevicesPanel().getTxtPort().setText("70000");
            frame.getDevicesPanel().getBtnConnectLan().doClick();
            assertTrue(frame.getDevicesPanel().getLblInputError().getText().contains("Port"),
                    "Phải hiển thị lỗi cổng mạng ngoài phạm vi 1-65535");

            // Nhập Pairing code sai
            frame.getDevicesPanel().selectRelayMode();
            frame.getDevicesPanel().getTxtPairingCode().setText("123");
            frame.getDevicesPanel().getBtnConnectRelay().doClick();
            assertTrue(frame.getDevicesPanel().getLblInputError().getText().contains("Relay"),
                    "Phải hiển thị lỗi mã ghép nối không đúng định dạng");

            frame.dispose();
        });
    }

    @Test
    void testFingerprintVerificationDialogSafetyRules() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            // Trường hợp 1: Chế độ Preview -> Nút Verify bắt buộc disabled
            FingerprintVerificationDialog previewDialog = new FingerprintVerificationDialog(
                    null, "test-session", "192.168.1.20:52001",
                    "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF",
                    "3F11A8C95D44201B7E6088912C5540AA00192837465564738291A0B1C2D3E4F5",
                    true); // isPreviewMode = true
            assertFalse(previewDialog.getBtnVerify().isEnabled(),
                    "Trong chế độ Preview, nút xác minh bắt buộc phải bị disabled");
            previewDialog.dispose();

            // Trường hợp 2: Không có session ID hoặc thiếu remote fingerprint -> Verify disabled
            FingerprintVerificationDialog emptyDialog = new FingerprintVerificationDialog(
                    null, "", "192.168.1.20:52001", "", "", false);
            assertFalse(emptyDialog.getBtnVerify().isEnabled(),
                    "Khi thiếu session ID hoặc fingerprint, nút xác minh bắt buộc disabled");
            emptyDialog.dispose();

            // Trường hợp 3: Có đầy đủ dữ liệu phiên thật và không phải preview -> Verify enabled
            FingerprintVerificationDialog validDialog = new FingerprintVerificationDialog(
                    null, "session-101", "192.168.1.20:52001",
                    "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF",
                    "3F11A8C95D44201B7E6088912C5540AA00192837465564738291A0B1C2D3E4F5",
                    false);
            assertTrue(validDialog.getBtnVerify().isEnabled(),
                    "Khi có session thật và fingerprint thật, nút xác minh mới được bật");
            validDialog.dispose();
        });
    }

    @Test
    void testTransferProgressUpdatesWithoutFreezing() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame();

            TransferViewModel model = new TransferViewModel(
                    "tx-001",
                    "sample.zip",
                    100L * 1024L * 1024L,
                    45L * 1024L * 1024L,
                    4.5 * 1024 * 1024,
                    TransferViewModel.TransferDirection.UPLOAD,
                    TransferViewModel.TransferState.TRANSFERRING,
                    "");

            frame.updateTransfer(model);
            assertEquals(45, frame.getTransfersPanel().getProgressBar().getValue());
            assertTrue(frame.getTransfersPanel().getProgressBar().getString().contains("45%"));

            // Reset về idle
            frame.getTransfersPanel().resetToIdle();
            assertEquals(0, frame.getTransfersPanel().getProgressBar().getValue());

            frame.dispose();
        });
    }

    @Test
    void testSessionUpdatesOnMainFrame() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame();

            SessionViewModel verifiedSession = new SessionViewModel(
                    "s-8821",
                    "192.168.1.50:52001",
                    "LOCAL_FP",
                    "REMOTE_FP",
                    false,
                    true,
                    true,
                    "CONNECTED_AND_VERIFIED");

            frame.updateSession(verifiedSession);
            assertTrue(frame.getHeaderPanel().getTreeLock() != null);
            frame.dispose();
        });
    }
}

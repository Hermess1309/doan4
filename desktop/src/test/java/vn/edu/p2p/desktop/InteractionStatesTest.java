package vn.edu.p2p.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.util.ThemeManager;
import vn.edu.p2p.desktop.view.MainFrame;
import vn.edu.p2p.desktop.view.dialogs.FingerprintVerificationDialog;

class InteractionStatesTest {

    private static final String VALID_FP_64 = "E47B9A128C3DF055A1B2C3D499887766102F4E6DAABBCCDD0123456789ABCDEF";
    private static final String LOCAL_FP_64 = "3F11A8C95D44201B7E6088912C5540AA00192837465564738291A0B1C2D3E4F5";

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
            // Trường hợp 1: Chế độ Preview -> Nút StartTls bắt buộc disabled ngay cả khi nhập đúng vân tay
            FingerprintVerificationDialog previewDialog = new FingerprintVerificationDialog(
                    null, "test-session", "192.168.1.20:52001",
                    LOCAL_FP_64, VALID_FP_64, true); // isPreviewMode = true
            assertFalse(previewDialog.getBtnStartTls().isEnabled(),
                    "Trong chế độ Preview, nút bắt tay TLS bắt buộc phải bị disabled");
            previewDialog.dispose();

            // Trường hợp 2: Không có session ID -> StartTls disabled
            FingerprintVerificationDialog noSessionDialog = new FingerprintVerificationDialog(
                    null, "", "192.168.1.20:52001", LOCAL_FP_64, VALID_FP_64, false);
            assertFalse(noSessionDialog.getBtnStartTls().isEnabled(),
                    "Khi thiếu session ID, nút bắt tay TLS bắt buộc disabled");
            noSessionDialog.dispose();

            // Trường hợp 3: Nhập vân tay rỗng hoặc sai định dạng -> StartTls disabled
            FingerprintVerificationDialog invalidInput = new FingerprintVerificationDialog(
                    null, "session-101", "192.168.1.20:52001", LOCAL_FP_64, "invalid_fp", false);
            assertFalse(invalidInput.getBtnStartTls().isEnabled(),
                    "Khi vân tay sai cú pháp, nút bắt tay TLS bắt buộc disabled");
            assertTrue(invalidInput.getLblValidationFeedback().getText().contains("Lỗi"));
            invalidInput.dispose();

            // Trường hợp 4: Có đầy đủ session thật và nhập đúng vân tay 64 hex -> StartTls enabled
            FingerprintVerificationDialog validDialog = new FingerprintVerificationDialog(
                    null, "session-101", "192.168.1.20:52001", LOCAL_FP_64, "", false);
            assertFalse(validDialog.getBtnStartTls().isEnabled());

            // Gõ vân tay hợp lệ vào ô input
            validDialog.getTxtRemoteFingerprintInput().setText(VALID_FP_64);
            assertTrue(validDialog.getBtnStartTls().isEnabled(),
                    "Khi nhập đúng 64 hex và có session thật, nút bắt tay TLS mới được bật");

            // Bấm bắt tay -> kích hoạt callback chuyển giao canonical fingerprint
            AtomicReference<String> confirmedPin = new AtomicReference<>();
            validDialog.setOnFingerprintConfirmed(confirmedPin::set);
            validDialog.getBtnStartTls().doClick();
            assertEquals(VALID_FP_64, confirmedPin.get());

            validDialog.dispose();
        });
    }

    @Test
    void testSendButtonRequiresSecureReadyState(@TempDir Path tempDir) throws IOException, InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        Path tempFile = Files.createFile(tempDir.resolve("sample.pdf"));
        File validFile = tempFile.toFile();

        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame();

            // 1. Khi ở trạng thái TCP_CONNECTED (chưa TLS) -> Send disabled
            SessionViewModel tcpSession = new SessionViewModel(
                    "s-01", "192.168.1.20:52001", LOCAL_FP_64, "", false,
                    SessionState.TCP_CONNECTED, "");
            frame.updateSession(tcpSession);
            assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled(),
                    "Nút Send không được phép bật khi mới chỉ kết nối TCP chưa TLS");

            // 2. Khi ở trạng thái WAITING_FOR_FINGERPRINT -> Send disabled
            SessionViewModel waitingSession = new SessionViewModel(
                    "s-01", "192.168.1.20:52001", LOCAL_FP_64, "", false,
                    SessionState.WAITING_FOR_FINGERPRINT, "");
            frame.updateSession(waitingSession);
            assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled());

            // 3. Khi ở trạng thái TLS_HANDSHAKING -> Send disabled
            SessionViewModel handshakingSession = new SessionViewModel(
                    "s-01", "192.168.1.20:52001", LOCAL_FP_64, VALID_FP_64, false,
                    SessionState.TLS_HANDSHAKING, "");
            frame.updateSession(handshakingSession);
            assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled());

            // 4. Khi ở trạng thái SECURE_READY nhưng chưa chọn file -> Send disabled
            SessionViewModel secureSession = new SessionViewModel(
                    "s-01", "192.168.1.20:52001", LOCAL_FP_64, VALID_FP_64, false,
                    SessionState.SECURE_READY, "");
            frame.updateSession(secureSession);
            assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled(),
                    "Chưa chọn file thì nút Send vẫn phải disabled");

            // 5. Khi ngắt kết nối hoặc có lỗi -> Send disabled ngay lập tức
            SessionViewModel errorSession = new SessionViewModel(
                    "s-01", "192.168.1.20:52001", LOCAL_FP_64, "", false,
                    SessionState.ERROR, "Handshake failed");
            frame.updateSession(errorSession);
            assertFalse(frame.getDashboardPanel().getBtnSend().isEnabled());

            frame.dispose();
        });
    }

    @Test
    void testSecurityPanelTransitionsToHandshakingNotReady() throws InterruptedException, InvocationTargetException {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame();

            // Giả lập phiên kết nối TCP chờ đối chiếu
            SessionViewModel tcpSession = new SessionViewModel(
                    "s-8821", "192.168.1.50:52001", LOCAL_FP_64, "", false,
                    SessionState.TCP_CONNECTED, "");
            frame.updateSession(tcpSession);

            assertTrue(frame.getSecurityPanel().getLblVerificationBadge().getText().contains("TCP"));
            assertTrue(frame.getSecurityPanel().getBtnOpenVerificationDialog().isEnabled());

            // Khi người dùng bấm xác nhận trong dialog, SecurityPanel chuyển sang TLS_HANDSHAKING (chứ KHÔNG sang SECURE_READY)
            frame.getSecurityPanel().updateSession(new SessionViewModel(
                    "s-8821", "192.168.1.50:52001", LOCAL_FP_64, VALID_FP_64, false,
                    SessionState.TLS_HANDSHAKING, ""));

            assertTrue(frame.getSecurityPanel().getLblVerificationBadge().getText().contains("BẮT TAY MUTUAL TLS"));
            assertFalse(frame.getSecurityPanel().getBtnOpenVerificationDialog().isEnabled(),
                    "Đang bắt tay TLS thì không được mở lại dialog xác minh");

            frame.dispose();
        });
    }
}

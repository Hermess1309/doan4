package vn.edu.p2p.desktop.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.core.PeerNode;
import vn.edu.p2p.desktop.model.SessionViewModel;
import vn.edu.p2p.desktop.model.SessionViewModel.SessionState;
import vn.edu.p2p.desktop.model.TransferViewModel;
import vn.edu.p2p.desktop.model.TransferViewModel.TransferState;
import vn.edu.p2p.desktop.util.ValidationUtils;

@Timeout(60)
class RealCoreApiAdapterTest {

    @TempDir
    static Path identitiesDir;

    @TempDir
    Path testWorkspace;

    private static char[] testPassword;
    private static PeerNode aliceNode;
    private static PeerNode bobNode;

    private RealCoreApiAdapter adapterAlice;
    private RealCoreApiAdapter adapterBob;

    @BeforeAll
    static void generateIdentities() throws Exception {
        byte[] randomBytes = new byte[16];
        new SecureRandom().nextBytes(randomBytes);
        testPassword = HexFormat.of().formatHex(randomBytes).toCharArray();
        Arrays.fill(randomBytes, (byte) 0);
        Path aliceStore = identitiesDir.resolve("alice.p12");
        Path bobStore = identitiesDir.resolve("bob.p12");

        LocalIdentityHelper.generateIdentityWithKeytool(aliceStore, testPassword, "peer");
        LocalIdentityHelper.generateIdentityWithKeytool(bobStore, testPassword, "peer");

        aliceNode = LocalIdentityHelper.load(aliceStore, testPassword, "peer");
        bobNode = LocalIdentityHelper.load(bobStore, testPassword, "peer");
    }

    @AfterAll
    static void clearPassword() {
        if (testPassword != null) {
            Arrays.fill(testPassword, '\0');
        }
    }

    @BeforeEach
    void setUpAdapters() throws Exception {
        // Cổng 0 để hệ điều hành tự cấp phát cổng trống, không xung đột
        adapterAlice = RealCoreApiAdapter.create(aliceNode, 0);
        adapterBob = RealCoreApiAdapter.create(bobNode, 0);
    }

    @AfterEach
    void tearDownAdapters() {
        if (adapterAlice != null) {
            adapterAlice.shutdown();
        }
        if (adapterBob != null) {
            adapterBob.shutdown();
        }
    }

    @Test
    void testLocalFingerprintRetrievable() {
        String fpAlice = adapterAlice.getLocalFingerprint();
        String fpBob = adapterBob.getLocalFingerprint();

        assertNotNull(fpAlice);
        assertNotNull(fpBob);
        assertTrue(ValidationUtils.isValidFingerprint(fpAlice), "Fingerprint Alice phải đúng 32 bytes hex SHA-256");
        assertTrue(ValidationUtils.isValidFingerprint(fpBob), "Fingerprint Bob phải đúng 32 bytes hex SHA-256");
        assertEquals(64, fpAlice.length());
        assertEquals(64, fpBob.length());
    }

    @Test
    void testSuccessfulTlsHandshakeAndFileTransfer() throws Exception {
        int bobPort = adapterBob.getListenPort();
        assertTrue(bobPort > 0, "Bob phải đang lắng nghe trên cổng khả dụng");

        AtomicReference<SessionViewModel> bobSessionRef = new AtomicReference<>();
        CountDownLatch bobIncomingConnectionLatch = new CountDownLatch(1);

        adapterBob.registerListeners(
                sessionVm -> {
                    if (sessionVm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                        bobSessionRef.set(sessionVm);
                        bobIncomingConnectionLatch.countDown();
                    }
                },
                null
        );

        // 1. Alice kết nối TCP tới Bob
        SessionViewModel aliceSession = adapterAlice.connectDirect("127.0.0.1", bobPort).get(10, TimeUnit.SECONDS);
        assertNotNull(aliceSession);
        assertEquals(SessionState.WAITING_FOR_FINGERPRINT, aliceSession.state());

        // Đợi Bob chấp nhận kết nối TCP
        assertTrue(bobIncomingConnectionLatch.await(10, TimeUnit.SECONDS), "Bob phải nhận được kết nối TCP từ Alice");
        SessionViewModel bobSession = bobSessionRef.get();
        assertNotNull(bobSession);

        // 2. Trao đổi Fingerprint qua kênh độc lập và Bắt tay TLS 1.3
        CompletableFuture<SessionViewModel> aliceHandshake = adapterAlice.startTlsHandshake(
                aliceSession.sessionId(),
                adapterBob.getLocalFingerprint()
        );

        CompletableFuture<SessionViewModel> bobHandshake = adapterBob.startTlsHandshake(
                bobSession.sessionId(),
                adapterAlice.getLocalFingerprint()
        );

        SessionViewModel aliceSecure = aliceHandshake.get(15, TimeUnit.SECONDS);
        SessionViewModel bobSecure = bobHandshake.get(15, TimeUnit.SECONDS);

        assertEquals(SessionState.SECURE_READY, aliceSecure.state(), "Alice phải đạt SECURE_READY sau handshake");
        assertEquals(SessionState.SECURE_READY, bobSecure.state(), "Bob phải đạt SECURE_READY sau handshake");
        assertTrue(aliceSecure.isReadyToTransfer());
        assertTrue(bobSecure.isReadyToTransfer());

        // 3. Chuẩn bị tệp tin gửi đi
        Path sourcePath = testWorkspace.resolve("tai-lieu.txt");
        String content = "Xin chào, đây là kiểm thử truyền tệp P2P End-to-End qua TLS 1.3!\nUTF-8: Tiếng Việt có dấu.";
        Files.writeString(sourcePath, content, StandardCharsets.UTF_8);
        String expectedSha256 = calculateSha256(sourcePath);

        Path destPath = testWorkspace.resolve("tai-lieu-nhan-duoc.txt");
        assertFalse(Files.exists(destPath), "Tệp đích ban đầu không được tồn tại");

        // 4. Thiết lập bộ xử lý chấp nhận tệp phía Bob (Receiver)
        CountDownLatch transferCompletedLatch = new CountDownLatch(2);
        List<TransferViewModel> aliceProgressList = new ArrayList<>();
        List<TransferViewModel> bobProgressList = new ArrayList<>();

        adapterBob.setIncomingOfferHandler((offer, decisionCallback) -> {
            assertEquals("tai-lieu.txt", offer.fileName());
            assertEquals(sourcePath.toFile().length(), offer.size());
            assertEquals(expectedSha256, offer.sha256());
            // Đồng ý nhận và lưu vào destPath
            decisionCallback.accept(destPath.toFile());
        });

        adapterAlice.registerListeners(null, tvm -> {
            aliceProgressList.add(tvm);
            if (tvm.state() == TransferState.COMPLETED) {
                transferCompletedLatch.countDown();
            }
        });

        adapterBob.registerListeners(null, tvm -> {
            bobProgressList.add(tvm);
            if (tvm.state() == TransferState.COMPLETED) {
                transferCompletedLatch.countDown();
            }
        });

        // 5. Alice bắt đầu gửi tệp
        CompletableFuture<String> sendFuture = adapterAlice.offerFile(aliceSecure.sessionId(), sourcePath.toFile());
        String transferId = sendFuture.get(15, TimeUnit.SECONDS);
        assertNotNull(transferId);

        // 6. Kiểm tra hoàn tất truyền tải
        assertTrue(transferCompletedLatch.await(15, TimeUnit.SECONDS), "Cả hai bên phải hoàn tất truyền tải");
        assertTrue(Files.exists(destPath), "Tệp đích phải được tạo thành công trên đĩa");
        assertEquals(Files.size(sourcePath), Files.size(destPath), "Kích thước tệp gửi và nhận phải khớp nhau");
        assertEquals(expectedSha256, calculateSha256(destPath), "Băm SHA-256 tệp nhận phải khớp tuyệt đối với tệp gốc");
    }

    @Test
    void testFileRejectionByReceiver() throws Exception {
        int bobPort = adapterBob.getListenPort();
        CountDownLatch bobIncomingLatch = new CountDownLatch(1);
        AtomicReference<SessionViewModel> bobSessionRef = new AtomicReference<>();

        adapterBob.registerListeners(
                sessionVm -> {
                    if (sessionVm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                        bobSessionRef.set(sessionVm);
                        bobIncomingLatch.countDown();
                    }
                },
                null
        );

        SessionViewModel aliceSession = adapterAlice.connectDirect("127.0.0.1", bobPort).get(10, TimeUnit.SECONDS);
        assertTrue(bobIncomingLatch.await(10, TimeUnit.SECONDS));

        // Handshake hợp lệ
        CompletableFuture<SessionViewModel> aliceHandshake = adapterAlice.startTlsHandshake(
                aliceSession.sessionId(), adapterBob.getLocalFingerprint());
        CompletableFuture<SessionViewModel> bobHandshake = adapterBob.startTlsHandshake(
                bobSessionRef.get().sessionId(), adapterAlice.getLocalFingerprint());

        aliceHandshake.get(10, TimeUnit.SECONDS);
        bobHandshake.get(10, TimeUnit.SECONDS);

        // Bob từ chối khi có offer
        adapterBob.setIncomingOfferHandler((offer, decisionCallback) -> {
            decisionCallback.accept(null); // Từ chối nhận tệp
        });

        Path sourcePath = testWorkspace.resolve("tu-choi.bin");
        Files.write(sourcePath, new byte[]{1, 2, 3, 4, 5});

        CompletableFuture<String> sendFuture = adapterAlice.offerFile(aliceSession.sessionId(), sourcePath.toFile());

        // Phía gửi phải nhận được Exception do đối tác từ chối
        assertThrows(ExecutionException.class, () -> sendFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    void testHandshakeFailsOnMismatchedFingerprint() throws Exception {
        int bobPort = adapterBob.getListenPort();
        CountDownLatch bobIncomingLatch = new CountDownLatch(1);
        AtomicReference<SessionViewModel> bobSessionRef = new AtomicReference<>();

        adapterBob.registerListeners(
                sessionVm -> {
                    if (sessionVm.state() == SessionState.WAITING_FOR_FINGERPRINT) {
                        bobSessionRef.set(sessionVm);
                        bobIncomingLatch.countDown();
                    }
                },
                null
        );

        SessionViewModel aliceSession = adapterAlice.connectDirect("127.0.0.1", bobPort).get(10, TimeUnit.SECONDS);
        assertTrue(bobIncomingLatch.await(10, TimeUnit.SECONDS));

        // Alice cung cấp sai vân tay (64 ký tự 0)
        String fakeFingerprint = "0000000000000000000000000000000000000000000000000000000000000000";

        CompletableFuture<SessionViewModel> aliceHandshake = adapterAlice.startTlsHandshake(
                aliceSession.sessionId(), fakeFingerprint);
        CompletableFuture<SessionViewModel> bobHandshake = adapterBob.startTlsHandshake(
                bobSessionRef.get().sessionId(), adapterAlice.getLocalFingerprint());

        assertThrows(ExecutionException.class, () -> aliceHandshake.get(10, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> bobHandshake.get(10, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownReleasesListenerAndExecutor() {
        int bobPort = adapterBob.getListenPort();
        assertTrue(bobPort > 0);
        assertFalse(adapterBob.isShutdown());

        adapterBob.shutdown();
        assertTrue(adapterBob.isShutdown(), "shutdown() phải đóng listener và tắt worker executor");

        // Kết nối mới vào cổng đã đóng phải thất bại
        assertThrows(
                ExecutionException.class,
                () -> adapterAlice.connectDirect("127.0.0.1", bobPort).get(5, TimeUnit.SECONDS));
    }

    private static String calculateSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        return HexFormat.of().withUpperCase().formatHex(digest.digest(bytes));
    }
}

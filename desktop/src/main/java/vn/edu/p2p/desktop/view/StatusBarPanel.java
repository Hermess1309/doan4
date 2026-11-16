package vn.edu.p2p.desktop.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Thanh trạng thái (Status Bar) dưới cùng của ứng dụng.
 */
public class StatusBarPanel extends JPanel {

    private final JLabel lblEngineStatus;
    private final JLabel lblLocalInfo;
    private final JLabel lblNetworkSpeed;

    public StatusBarPanel() {
        setLayout(new BorderLayout(12, 0));
        setPreferredSize(new Dimension(0, 28));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 63, 65)),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));

        lblEngineStatus = new JLabel("Trạng thái: Sẵn sàng (M0 Scaffold)");
        lblEngineStatus.setFont(lblEngineStatus.getFont().deriveFont(Font.PLAIN, 11f));
        add(lblEngineStatus, BorderLayout.WEST);

        JPanel rightGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        lblNetworkSpeed = new JLabel("↑ 0.0 KB/s   ↓ 0.0 KB/s");
        lblNetworkSpeed.setFont(lblNetworkSpeed.getFont().deriveFont(Font.PLAIN, 11f));

        lblLocalInfo = new JLabel("LAN Port: 52001");
        lblLocalInfo.setFont(lblLocalInfo.getFont().deriveFont(Font.PLAIN, 11f));

        rightGroup.add(lblNetworkSpeed);
        rightGroup.add(new JLabel("|"));
        rightGroup.add(lblLocalInfo);
        add(rightGroup, BorderLayout.EAST);
    }

    public void setEngineStatus(String status) {
        lblEngineStatus.setText("Trạng thái: " + status);
    }

    public void setNetworkSpeed(String speedText) {
        lblNetworkSpeed.setText(speedText);
    }

    public void setLocalInfo(String info) {
        lblLocalInfo.setText(info);
    }
}

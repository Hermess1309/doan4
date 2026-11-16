package vn.edu.p2p.desktop.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Thanh điều hướng dọc bên trái (Sidebar Navigation Rail).
 */
public class SidebarPanel extends JPanel {

    public static final String TAB_DASHBOARD = "DASHBOARD";
    public static final String TAB_DEVICES = "DEVICES";
    public static final String TAB_TRANSFERS = "TRANSFERS";
    public static final String TAB_SECURITY = "SECURITY";
    public static final String TAB_SETTINGS = "SETTINGS";

    private final List<JButton> navButtons = new ArrayList<>();
    private Consumer<String> onNavigate;

    public SidebarPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(210, 0));
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(60, 63, 65)));

        // 1. Logo & App Title (North)
        JPanel brandPanel = new JPanel();
        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        brandPanel.setBorder(BorderFactory.createEmptyBorder(20, 16, 20, 16));

        JLabel lblLogo = new JLabel("P2P TRANSFER");
        lblLogo.setFont(lblLogo.getFont().deriveFont(Font.BOLD, 16f));
        lblLogo.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lblSubtitle = new JLabel("Secure E2E • TLS 1.3");
        lblSubtitle.setFont(lblSubtitle.getFont().deriveFont(Font.PLAIN, 11f));
        lblSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        brandPanel.add(lblLogo);
        brandPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        brandPanel.add(lblSubtitle);
        add(brandPanel, BorderLayout.NORTH);

        // 2. Navigation List (Center)
        JPanel navContainer = new JPanel();
        navContainer.setLayout(new BoxLayout(navContainer, BoxLayout.Y_AXIS));
        navContainer.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JButton btnDash = createNavButton(" Tổng quan", TAB_DASHBOARD);
        JButton btnDev = createNavButton(" Thiết bị & Mạng", TAB_DEVICES);
        JButton btnTrans = createNavButton(" Tiến trình truyền", TAB_TRANSFERS);
        JButton btnSec = createNavButton(" Bảo mật vân tay", TAB_SECURITY);
        JButton btnSet = createNavButton(" Cài đặt", TAB_SETTINGS);

        navContainer.add(btnDash);
        navContainer.add(Box.createRigidArea(new Dimension(0, 6)));
        navContainer.add(btnDev);
        navContainer.add(Box.createRigidArea(new Dimension(0, 6)));
        navContainer.add(btnTrans);
        navContainer.add(Box.createRigidArea(new Dimension(0, 6)));
        navContainer.add(btnSec);
        navContainer.add(Box.createRigidArea(new Dimension(0, 6)));
        navContainer.add(btnSet);
        navContainer.add(Box.createVerticalGlue());

        add(navContainer, BorderLayout.CENTER);

        // 3. Footer (South)
        JPanel footerPanel = new JPanel();
        footerPanel.setLayout(new BoxLayout(footerPanel, BoxLayout.Y_AXIS));
        footerPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));

        JLabel lblCoreStatus = new JLabel("● Core: M0 Scaffold");
        lblCoreStatus.setFont(lblCoreStatus.getFont().deriveFont(Font.PLAIN, 11f));
        lblCoreStatus.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lblVersion = new JLabel("Phiên bản v0.1.0");
        lblVersion.setFont(lblVersion.getFont().deriveFont(Font.PLAIN, 10f));
        lblVersion.setAlignmentX(Component.LEFT_ALIGNMENT);

        footerPanel.add(lblCoreStatus);
        footerPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        footerPanel.add(lblVersion);
        add(footerPanel, BorderLayout.SOUTH);

        // Mặc định chọn Dashboard
        setSelectedTab(TAB_DASHBOARD);
    }

    private JButton createNavButton(String text, String tabKey) {
        JButton btn = new JButton(text);
        btn.setActionCommand(tabKey);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        btn.setPreferredSize(new Dimension(185, 40));
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 13f));
        btn.setFocusPainted(false);

        btn.addActionListener(e -> {
            setSelectedTab(tabKey);
            if (onNavigate != null) {
                onNavigate.accept(tabKey);
            }
        });

        navButtons.add(btn);
        return btn;
    }

    public void setSelectedTab(String tabKey) {
        for (JButton btn : navButtons) {
            boolean active = btn.getActionCommand().equals(tabKey);
            btn.setFont(btn.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        }
    }

    public void setOnNavigate(Consumer<String> onNavigate) {
        this.onNavigate = onNavigate;
    }
}

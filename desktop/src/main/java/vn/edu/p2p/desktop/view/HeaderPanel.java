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
 * Thanh tiêu đề trên cùng của vùng nội dung chính (Header Panel).
 */
public class HeaderPanel extends JPanel {

    private final JLabel lblTitle;
    private final JLabel lblConnectionBadge;

    public HeaderPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(0, 48));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 63, 65)),
                BorderFactory.createEmptyBorder(10, 24, 10, 24)));

        lblTitle = new JLabel("Tổng quan");
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 16f));
        add(lblTitle, BorderLayout.WEST);

        JPanel rightGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        lblConnectionBadge = new JLabel("[ Chưa kết nối ]");
        lblConnectionBadge.setFont(lblConnectionBadge.getFont().deriveFont(Font.BOLD, 12f));
        lblConnectionBadge.setForeground(new Color(157, 160, 168));

        rightGroup.add(lblConnectionBadge);
        add(rightGroup, BorderLayout.EAST);
    }

    public void setTitle(String title) {
        lblTitle.setText(title);
    }

    public void setConnectionBadge(String text, Color color) {
        lblConnectionBadge.setText(text);
        if (color != null) {
            lblConnectionBadge.setForeground(color);
        }
    }
}

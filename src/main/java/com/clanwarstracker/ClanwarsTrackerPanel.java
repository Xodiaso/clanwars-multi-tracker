package com.clanwarstracker;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ClanwarsTrackerPanel extends PluginPanel
{
    private static final int    BANDAGE_HEAL   = 18;
    private static final Font   FONT_BOLD_13   = new Font("Arial", Font.BOLD, 13);
    private static final Font   FONT_BOLD_11   = new Font("Arial", Font.BOLD, 11);
    private static final Font   FONT_PLAIN_12  = new Font("Arial", Font.PLAIN, 12);
    private static final Color  BG_DARK        = new Color(40,  40,  40);
    private static final Color  BG_CARD        = new Color(55,  55,  55);
    private static final Color  BG_SUMMARY     = new Color(45,  45,  65);
    private static final Color  BORDER_COLOR   = new Color(80,  80,  80);
    private static final Color  COLOR_GOLD     = new Color(255, 200,  50);
    private static final Color  COLOR_WHITE    = Color.WHITE;
    private static final Color  COLOR_GREEN    = new Color(180, 230, 180);
    private static final Color  COLOR_CYAN     = new Color(100, 210, 255);
    private static final Color  COLOR_RED_BTN  = new Color(140,  40,  40);
    private static final Color  COLOR_BLUE_BTN = new Color(40,   80, 160);

    private final ClanwarsTrackerPlugin plugin;

    private final ImageIcon zgsIcon;
    private final ImageIcon barrageIcon;

    // Summary widgets
    private final JLabel summaryZgsLine1;
    private final JLabel summaryZgsLine2;
    private final JLabel summaryBarrageLine;

    // Player cards — all access is on the EDT only
    private final JPanel              targetListPanel;
    private final Map<String, JPanel> targetPanels = new HashMap<>();
    private final Map<String, JLabel> damageLabels = new HashMap<>();
    private final Map<String, JLabel> bandsLabels  = new HashMap<>();
    // index 0 = count/rate line, index 1 = reserved (hidden)
    private final Map<String, JLabel[]> specLabels = new HashMap<>();

    public ClanwarsTrackerPanel(ClanwarsTrackerPlugin plugin)
    {
        this.plugin = plugin;

        zgsIcon     = loadIcon("/clanwarstracker/zgs_icon.png");
        barrageIcon = loadIcon("/clanwarstracker/barrage_icon.png");

        setLayout(new BorderLayout());
        setBackground(BG_DARK);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // ── Title ──────────────────────────────────────────
        JLabel title = new JLabel("Clanwars Tracker");
        title.setForeground(COLOR_WHITE);
        title.setFont(FONT_BOLD_13);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBorder(new EmptyBorder(0, 0, 5, 0));

        // ── Summary card ───────────────────────────────────
        JPanel summaryCard = new JPanel();
        summaryCard.setLayout(new BoxLayout(summaryCard, BoxLayout.Y_AXIS));
        summaryCard.setBackground(BG_SUMMARY);
        summaryCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 160), 1),
                new EmptyBorder(6, 8, 6, 8)
        ));

        JLabel summaryTitle = new JLabel("Lifetime Stats");
        summaryTitle.setForeground(COLOR_CYAN);
        summaryTitle.setFont(FONT_BOLD_11);
        summaryTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        summaryZgsLine1 = new JLabel("ZGS: 0 specs | 0 hit (0%)");
        summaryZgsLine1.setForeground(COLOR_WHITE);
        summaryZgsLine1.setFont(FONT_PLAIN_12);
        summaryZgsLine1.setAlignmentX(Component.LEFT_ALIGNMENT);

        summaryZgsLine2 = new JLabel("ZGS splash: 0");
        summaryZgsLine2.setForeground(COLOR_WHITE);
        summaryZgsLine2.setFont(FONT_PLAIN_12);
        summaryZgsLine2.setAlignmentX(Component.LEFT_ALIGNMENT);

        summaryBarrageLine = new JLabel("Barrage: 0 casts | 0 landed (0%)");
        summaryBarrageLine.setForeground(COLOR_WHITE);
        summaryBarrageLine.setFont(FONT_PLAIN_12);
        summaryBarrageLine.setAlignmentX(Component.LEFT_ALIGNMENT);

        summaryCard.add(summaryTitle);
        summaryCard.add(Box.createVerticalStrut(3));
        summaryCard.add(summaryZgsLine1);
        summaryCard.add(Box.createVerticalStrut(1));
        summaryCard.add(summaryZgsLine2);
        summaryCard.add(Box.createVerticalStrut(1));
        summaryCard.add(summaryBarrageLine);

        // ── Reset All ──────────────────────────────────────
        JButton resetAllBtn = new JButton("Reset All");
        resetAllBtn.addActionListener(e ->
        {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Clear all damage stats and lifetime totals?",
                    "Reset All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) plugin.resetAll();
        });
        styleButton(resetAllBtn, new Color(180, 50, 50));

        // ── Top panel ──────────────────────────────────────
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(BG_DARK);
        topPanel.add(title);
        topPanel.add(Box.createVerticalStrut(5));
        topPanel.add(summaryCard);
        topPanel.add(Box.createVerticalStrut(6));
        topPanel.add(resetAllBtn);
        topPanel.add(Box.createVerticalStrut(6));

        add(topPanel, BorderLayout.NORTH);

        // ── Player list ────────────────────────────────────
        targetListPanel = new JPanel();
        targetListPanel.setLayout(new BoxLayout(targetListPanel, BoxLayout.Y_AXIS));
        targetListPanel.setBackground(BG_DARK);

        add(targetListPanel, BorderLayout.CENTER);
    }

    private ImageIcon loadIcon(String path)
    {
        try
        {
            BufferedImage img = ImageUtil.loadImageResource(getClass(), path);
            if (img != null)
            {
                Image scaled = img.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            }
        }
        catch (Exception e)
        {
            // Icon not found — label will display text only
        }
        return null;
    }

    private void styleButton(JButton btn, Color bg)
    {
        btn.setBackground(bg);
        btn.setForeground(COLOR_WHITE);
        btn.setFocusPainted(false);
        btn.setFont(FONT_PLAIN_12);
        btn.setBorder(new EmptyBorder(3, 8, 3, 8));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
    }

    // -------------------------------------------------------
    // Summary update
    // -------------------------------------------------------

    public void updateSummary(ClanwarsTrackerPlugin.Role role,
                              int zgsSpecs,     int zgsHits,
                              int barrageCasts, int barrageLanded)
    {
        SwingUtilities.invokeLater(() ->
        {
            int zgsSplashes = zgsSpecs - zgsHits;
            int zgsHitPct   = zgsSpecs > 0 ? (int) Math.round((zgsHits * 100.0) / zgsSpecs) : 0;

            summaryZgsLine1.setText("ZGS: " + zgsSpecs + " specs | " + zgsHits + " hit (" + zgsHitPct + "%)");
            summaryZgsLine2.setText("ZGS splash: " + Math.max(0, zgsSplashes));

            int barLandedPct = barrageCasts > 0 ? (int) Math.round((barrageLanded * 100.0) / barrageCasts) : 0;
            summaryBarrageLine.setText("Barrage: " + barrageCasts + " casts | " + barrageLanded + " landed (" + barLandedPct + "%)");
        });
    }

    // -------------------------------------------------------
    // Player card update
    // NOTE: All map parameters must be snapshots (new HashMap copies),
    //       never live references from the plugin. The plugin is responsible
    //       for snapshotting before calling this method.
    // -------------------------------------------------------

    public void updateDamage(Map<String, Integer> damageMap,
                             Map<String, Integer> zgsHitsPerPlayer,
                             Map<String, Integer> zgsSplashesPerPlayer,
                             Map<String, Integer> barragecastsPerPlayer,
                             Map<String, Integer> barrageFreezesPerPlayer,
                             ClanwarsTrackerPlugin.Role role)
    {
        SwingUtilities.invokeLater(() ->
        {
            Map<String, Integer> sorted = damageMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (e1, e2) -> e1, LinkedHashMap::new));

            // Remove cards for players no longer in the damage map
            targetPanels.entrySet().removeIf(e ->
            {
                if (!damageMap.containsKey(e.getKey()))
                {
                    targetListPanel.remove(e.getValue());
                    damageLabels.remove(e.getKey());
                    bandsLabels.remove(e.getKey());
                    specLabels.remove(e.getKey());
                    return true;
                }
                return false;
            });

            for (Map.Entry<String, Integer> entry : sorted.entrySet())
            {
                String   name      = entry.getKey();
                int      damage    = entry.getValue();
                int      bands     = damage / BANDAGE_HEAL;
                String[] specLines = buildSpecLines(name, role,
                        zgsHitsPerPlayer, zgsSplashesPerPlayer,
                        barragecastsPerPlayer, barrageFreezesPerPlayer);

                if (!targetPanels.containsKey(name))
                {
                    addCard(name, damage, bands, specLines, role);
                }
                else
                {
                    JLabel d  = damageLabels.get(name);
                    JLabel b  = bandsLabels.get(name);
                    JLabel[] s = specLabels.get(name);
                    if (d != null) d.setText("Damage: " + damage);
                    if (b != null) b.setText("Est. Bands: " + bands);
                    if (s != null)
                    {
                        s[0].setIcon(getSpecIcon(role));
                        s[0].setText(specLines[0]);
                        s[1].setText(specLines[1]);
                        s[0].setVisible(!specLines[0].isEmpty());
                        s[1].setVisible(!specLines[1].isEmpty());
                    }
                }
            }

            targetListPanel.revalidate();
            targetListPanel.repaint();
            revalidate();
            repaint();
        });
    }

    /**
     * Builds the spec/barrage stat line for a single player card.
     * Returns [0] = display string, [1] = always "" (reserved).
     */
    private String[] buildSpecLines(String name,
                                    ClanwarsTrackerPlugin.Role role,
                                    Map<String, Integer> zgsHitsPerPlayer,
                                    Map<String, Integer> zgsSplashesPerPlayer,
                                    Map<String, Integer> barragecastsPerPlayer,
                                    Map<String, Integer> barrageFreezesPerPlayer)
    {
        if (role == ClanwarsTrackerPlugin.Role.DEFENDER)
        {
            int hits     = zgsHitsPerPlayer.getOrDefault(name, 0);
            int splashes = zgsSplashesPerPlayer.getOrDefault(name, 0);
            int total    = hits + splashes;
            int pct      = total > 0 ? (int) Math.round((hits * 100.0) / total) : 0;
            return new String[]{ total + "  \u2502  Landed: " + pct + "%", "" };
        }
        else if (role == ClanwarsTrackerPlugin.Role.MAGE)
        {
            int casts  = barragecastsPerPlayer.getOrDefault(name, 0);
            int landed = barrageFreezesPerPlayer.getOrDefault(name, 0);
            int pct    = casts > 0 ? (int) Math.round((landed * 100.0) / casts) : 0;
            return new String[]{ casts + "  \u2502  Landed: " + pct + "%", "" };
        }
        return new String[]{ "", "" };
    }

    private ImageIcon getSpecIcon(ClanwarsTrackerPlugin.Role role)
    {
        if (role == ClanwarsTrackerPlugin.Role.DEFENDER) return zgsIcon;
        if (role == ClanwarsTrackerPlugin.Role.MAGE)     return barrageIcon;
        return null;
    }

    // -------------------------------------------------------
    // Add player card
    // -------------------------------------------------------

    private void addCard(String name, int damage, int bands, String[] specLines, ClanwarsTrackerPlugin.Role role)
    {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(6, 6, 6, 6)
        ));

        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(COLOR_GOLD);
        nameLabel.setFont(FONT_BOLD_11);

        JLabel damageLabel = new JLabel("Damage: " + damage);
        damageLabel.setForeground(COLOR_WHITE);
        damageLabel.setFont(FONT_PLAIN_12);

        JLabel bandsLabel = new JLabel("Est. Bands: " + bands);
        bandsLabel.setForeground(COLOR_GREEN);
        bandsLabel.setFont(FONT_PLAIN_12);

        JLabel specLine0 = new JLabel(specLines[0], getSpecIcon(role), SwingConstants.LEFT);
        specLine0.setForeground(COLOR_CYAN);
        specLine0.setFont(FONT_PLAIN_12);
        specLine0.setIconTextGap(5);
        specLine0.setVisible(!specLines[0].isEmpty());

        JLabel specLine1 = new JLabel(specLines[1]);
        specLine1.setForeground(COLOR_CYAN);
        specLine1.setFont(FONT_PLAIN_12);
        specLine1.setVisible(!specLines[1].isEmpty());

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBackground(BG_CARD);
        infoPanel.add(nameLabel);
        infoPanel.add(Box.createVerticalStrut(2));
        infoPanel.add(damageLabel);
        infoPanel.add(Box.createVerticalStrut(1));
        infoPanel.add(bandsLabel);
        infoPanel.add(Box.createVerticalStrut(1));
        infoPanel.add(specLine0);
        if (!specLines[1].isEmpty())
        {
            infoPanel.add(Box.createVerticalStrut(1));
            infoPanel.add(specLine1);
        }

        JButton resetBtn = new JButton("Reset");
        resetBtn.addActionListener(e -> plugin.resetTarget(name));
        styleButton(resetBtn, COLOR_RED_BTN);

        JButton wipeStatsBtn = new JButton("Wipe Stats");
        wipeStatsBtn.addActionListener(e -> plugin.resetSpecData(name));
        styleButton(wipeStatsBtn, COLOR_BLUE_BTN);

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
        btnPanel.setBackground(BG_CARD);
        btnPanel.add(resetBtn);
        btnPanel.add(Box.createVerticalStrut(3));
        btnPanel.add(wipeStatsBtn);

        card.add(infoPanel, BorderLayout.CENTER);
        card.add(btnPanel, BorderLayout.EAST);

        damageLabels.put(name, damageLabel);
        bandsLabels.put(name, bandsLabel);
        specLabels.put(name, new JLabel[]{ specLine0, specLine1 });
        targetPanels.put(name, card);

        targetListPanel.add(card);
        targetListPanel.add(Box.createVerticalStrut(4));
    }
}

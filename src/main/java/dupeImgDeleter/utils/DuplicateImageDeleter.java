package dupeImgDeleter.utils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Locale;

public class DuplicateImageDeleter extends JFrame {

    private final JTextArea logArea;
    private final JProgressBar progressBar;
    private JButton startButton;

    // Persistent Radio Buttons for Config States
    private JRadioButton duplicateModeRadio;
    private JRadioButton similarModeRadio;
    private JRadioButton textModeRadio;
    private JRadioButton dryRunRadio;
    private JRadioButton deleteRadio;
    
    // Tracks the folder context persistently across subsequent button clicks
    private File lastSelectedDir;

    public DuplicateImageDeleter() {
        setTitle("Image Utility Toolkit");
        setSize(850, 450); // Widened slightly to accommodate the side control panel smoothly
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Center: Text Log Area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);
        
        lastSelectedDir = new File(System.getProperty("user.home"));

        // Right: Control Sidebar Panel
        JPanel sidePanel = createSidebarPanel();
        add(sidePanel, BorderLayout.EAST);

        // Bottom Panel for Progress Bar
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        bottomPanel.add(progressBar, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPanel createSidebarPanel() {
        JPanel sidePanel = new JPanel();
        sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
        sidePanel.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 10));

        // Feature Selector Group
        JPanel featurePanel = new JPanel();
        featurePanel.setLayout(new BoxLayout(featurePanel, BoxLayout.Y_AXIS));
        featurePanel.setBorder(BorderFactory.createTitledBorder("Scan Feature Mode"));

        duplicateModeRadio = new JRadioButton("Find Duplicate Images", true);
        similarModeRadio = new JRadioButton("Find Similar Images (Perceptual)", false);
        textModeRadio = new JRadioButton("Find White Text on Black BG", false);

        ButtonGroup featureGroup = new ButtonGroup();
        featureGroup.add(duplicateModeRadio);
        featureGroup.add(similarModeRadio);
        featureGroup.add(textModeRadio);

        featurePanel.add(duplicateModeRadio);
        featurePanel.add(Box.createRigidArea(new Dimension(0, 5)));
        featurePanel.add(similarModeRadio);
        featurePanel.add(Box.createRigidArea(new Dimension(0, 5)));
        featurePanel.add(textModeRadio);

        // Action Mode Selector Group
        JPanel actionPanel = new JPanel();
        actionPanel.setLayout(new BoxLayout(actionPanel, BoxLayout.Y_AXIS));
        actionPanel.setBorder(BorderFactory.createTitledBorder("Operation Mode"));

        dryRunRadio = new JRadioButton("Stage Matches (Dry Run)", true);
        deleteRadio = new JRadioButton("Permanently Delete", false);

        ButtonGroup actionGroup = new ButtonGroup();
        actionGroup.add(dryRunRadio);
        actionGroup.add(deleteRadio);

        actionPanel.add(dryRunRadio);
        actionPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        actionPanel.add(deleteRadio);

        // 3. Execution Action Button
        startButton = new JButton("Select Folder & Scan");
        startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        startButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        startButton.addActionListener(e -> selectAndRunScan());

        // Assembly
        sidePanel.add(featurePanel);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 15)));
        sidePanel.add(actionPanel);
        sidePanel.add(Box.createVerticalGlue()); // Push button to bottom of side section
        sidePanel.add(startButton);

        return sidePanel;
    }

    private void selectAndRunScan() {
        // Evaluate the persistent radio options directly
    	ScanStrategy selectedStrategy;
        if (duplicateModeRadio.isSelected()) {
            selectedStrategy = new DuplicateScanStrategy();
        } else if (similarModeRadio.isSelected()) {
            selectedStrategy = new SimilarImageScanStrategy();
        } else {
            selectedStrategy = new TextImageScanStrategy();
        }

        boolean isDryRun = dryRunRadio.isSelected();

        // Directory Selection Open dialog
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Directory to Scan");
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        
        // Pass the last selected directory to keep the user's place
        fileChooser.setCurrentDirectory(lastSelectedDir);

        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            logArea.append("No directory selected.\n");
            return;
        }
        
        // Update the pointer so subsequent runs start right here
        lastSelectedDir = fileChooser.getSelectedFile();

        // UI Component State Manipulation during scanning
        setControlsEnabled(false);
        logArea.setText("");
        progressBar.setIndeterminate(true);
        progressBar.setString("Processing runtime tasks...");

        // Kick off execution using the SwingWorker background thread pool
        ScanWorker worker = new ScanWorker(lastSelectedDir, isDryRun, selectedStrategy);
        worker.execute();
    }

    private void setControlsEnabled(boolean enabled) {
        startButton.setEnabled(enabled);
        duplicateModeRadio.setEnabled(enabled);
        similarModeRadio.setEnabled(enabled);
        textModeRadio.setEnabled(enabled);
        dryRunRadio.setEnabled(enabled);
        deleteRadio.setEnabled(enabled);
    }

    private class ScanWorker extends SwingWorker<Void, String> {
        private final File directory;
        private final boolean isDryRun;
        private final ScanStrategy strategy;

        public ScanWorker(File directory, boolean isDryRun, ScanStrategy strategy) {
            this.directory = directory;
            this.isDryRun = isDryRun;
            this.strategy = strategy;
        }

        @Override
        protected Void doInBackground() throws Exception {
            long startTime = System.currentTimeMillis();
            publish("Target folder: " + directory.getAbsolutePath());

            try {
                strategy.execute(directory, isDryRun, this::publish);
            } catch (Exception e) {
                publish("[FATAL ERROR] Strategy execution failed: " + e.getMessage());
            }

            long endTime = System.currentTimeMillis();
            publish("Total run time: " + formatDuration(endTime - startTime));
            return null;
        }

        private String formatDuration(long millis) {
            long totalSeconds = millis / 1000;
            if (totalSeconds < 60) {
                return String.format(Locale.US, "%.2f seconds", millis / 1000.0);
            } else {
                return String.format(Locale.US, "%d min, %d sec", totalSeconds / 60, totalSeconds % 60);
            }
        }

        @Override
        protected void process(List<String> chunks) {
            for (String message : chunks) {
                logArea.append(message + "\n");
            }
        }

        @Override
        protected void done() {
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            progressBar.setString("Finished");
            setControlsEnabled(true); // Bring back all controls to life safely
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new DuplicateImageDeleter().setVisible(true);
        });
    }
}
package org.cloudcoder.app.wizard.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.cloudcoder.app.wizard.exec.BootstrapStep;
import org.cloudcoder.app.wizard.exec.InstallationProgress;
import org.cloudcoder.app.wizard.exec.aws.AWSCloudService;
import org.cloudcoder.app.wizard.exec.aws.AWSInfo;
import org.cloudcoder.app.wizard.model.Document;
import org.cloudcoder.app.wizard.model.IValue;
import org.cloudcoder.app.wizard.model.ImmutableStringValue;
import org.cloudcoder.app.wizard.model.Page;
import org.cloudcoder.app.wizard.model.validators.IValidator;
import org.cloudcoder.app.wizard.model.validators.ValidationException;
import org.cloudcoder.app.wizard.ui.IWizardPagePanel.Type;

/**
 * Main UI for the installation wizard.
 * Coordinates progressing through the configuration UI and
 * then running the installation.
 * 
 * @author David Hovemeyer
 */
public class WizardPanel extends JPanel implements UIConstants {
	private static final String INSTALL_BUTTON_TEXT = "Install";
	private static final String NEXT_BUTTON_TEXT = "Next >>";
	private static final String PREV_BUTTON_TEXT = "<< Previous";

	private static final long serialVersionUID = 1L;
	
	private Document document;
	private List<IWizardPagePanel> wizardPagePanels;
	private int currentPage;
	private JLabel pageLabel;
	private JButton prevButton;
	private JButton nextButton;
	private JPanel pagePanel;
	private JLabel errorLabel;

	public WizardPanel() {
		wizardPagePanels = new ArrayList<IWizardPagePanel>();
		
		setPreferredSize(new Dimension(800, 600));

		setLayout(new BorderLayout());
		
		JPanel errorMessageAndButtonPanel = new JPanel();
		errorMessageAndButtonPanel.setLayout(new FlowLayout(FlowLayout.CENTER));
		
		this.errorLabel = new JLabel();
		errorLabel.setPreferredSize(new Dimension(SINGLE_COMPONENT_FIELD_WIDTH, FIELD_COMPONENT_HEIGHT));
		errorLabel.setForeground(Color.RED);
		errorMessageAndButtonPanel.add(errorLabel);
		
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));
		this.prevButton = new JButton(PREV_BUTTON_TEXT);
		prevButton.setPreferredSize(new Dimension(140, 26));
		this.nextButton = new JButton(NEXT_BUTTON_TEXT);
		nextButton.setPreferredSize(new Dimension(140, 26));
		buttonPanel.add(prevButton);
		buttonPanel.add(nextButton);
		buttonPanel.setPreferredSize(new Dimension(SINGLE_COMPONENT_FIELD_WIDTH, FIELD_COMPONENT_HEIGHT));
		errorMessageAndButtonPanel.add(buttonPanel);
		
		errorMessageAndButtonPanel.setPreferredSize(new Dimension(800, 96));
		add(errorMessageAndButtonPanel, BorderLayout.PAGE_END);
		
		this.pageLabel = new JLabel();
		pageLabel.setHorizontalAlignment(SwingConstants.CENTER);
		pageLabel.setPreferredSize(new Dimension(720, 64));
		pageLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 24));
		add(pageLabel, BorderLayout.PAGE_START);
		
		nextButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				onNext();
			}
		});
		prevButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				onPrevious();
			}
		});
		
		// Panel with CardLayout for displaying the WizardPagePanels
		this.pagePanel = new JPanel();
		pagePanel.setLayout(new CardLayout());
		add(pagePanel, BorderLayout.CENTER);
	}

	protected void onNext() {
		navigate(1);
	}

	protected void onPrevious() {
		navigate(-1);
	}

	private void navigate(int delta) {
		// If this is forward navigation, then validate and commit
		// the current page values (returning if validation fails.)
		// No validation is required to navigate backwards.
		if (delta > 0 && !doValidate()) {
			return;
		}
		commitCurrentValues();
		
		// Find the next enabled page
		int targetPage = currentPage;
		do {
			if (targetPage < 0 || targetPage >= document.getNumPages()) {
				System.err.printf("cannot find enabled page for delta %d, currentPage=%d", delta, currentPage);
				return;
			}
			targetPage += delta;
		} while (!document.isPageEnabled(targetPage));
		
		// Go to the target page
		currentPage = targetPage;
		changePage();
		
		// Start the installation if we have reached the install page
		if (wizardPagePanels.get(currentPage).getType() == Type.INSTALL) {
			onStartInstallation();
		}
	}

	private boolean doValidate() {
		Page page = document.get(currentPage);
		IWizardPagePanel p = wizardPagePanels.get(currentPage);
		if (p.getType() != IWizardPagePanel.Type.CONFIG) {
			return true;
		}
		ConfigPanel pp = p.asConfigPanel();
		pp.markAllValid();
		errorLabel.setText("");
		try {
			// Get Page with current UI values
			Page current = pp.getCurrentValues();
			
			// Validate all fields that haven't been selectively disabled
			for (int i = 0; i < page.getNumValues(); i++) {
				IValue origValue = page.get(i);
				if (current.isEnabled(page.get(i).getName())) {
					IValue updatedValue = current.get(i);
					IValidator validator = page.getValidator(i);
					validator.validate(current, origValue, updatedValue);
				}
			}
			return true;
		} catch (ValidationException e) {
			// Highlight the field that failed to validate
			for (int i = 0; i < page.getNumValues(); i++) {
				if (e.getOrigValue() == page.get(i)) {
					IPageField field = pp.getField(i);
					field.markInvalid();
					errorLabel.setText(e.getMessage());
				}
			}
			return false;
		}
	}
	
	private void commitCurrentValues() {
		Page page = document.get(currentPage);
		IWizardPagePanel p = wizardPagePanels.get(currentPage);
		if (p.getType() != IWizardPagePanel.Type.CONFIG) {
			return;
		}
		ConfigPanel pp = p.asConfigPanel();
		for (int i = 0; i < page.getNumValues(); i++) {
			IValue currentValue = pp.getField(i).getCurrentValue();
			page.set(i, currentValue);
		}
	}

	public void setDocument(Document document) {
		this.document = document;
		
		// Create WizardPagePanels
		for (int i = 0; i < document.getNumPages(); i++) {
			Page p = document.get(i);
			IWizardPagePanel pp;
			
			if (p.getPageName().equals("install")) {
				pp = new InstallPanel();
			} else {
				pp = new ConfigPanel();
			}
			// TODO: create a FinishedPanel
			
			pp.setPage(p);
			wizardPagePanels.add(pp);
			pagePanel.add(pp.asComponent(), String.valueOf(i));
		}
		
		currentPage = 0;
		changePage();
	}

	private void changePage() {
		Page page = document.get(currentPage);
		pageLabel.setText(page.getLabel());
		CardLayout cl = (CardLayout) pagePanel.getLayout();
		cl.show(pagePanel, String.valueOf(currentPage));
		
		// Enable prev/next buttons as appropriate.
		// The "special" pages don't allow manual navigation.
		boolean isInstallPage = page.getPageName().equals("install");
		boolean isErrorPage = page.getPageName().equals("error");
		boolean isFinishedPage = page.getPageName().equals("finished");
		boolean isSpecialPage = isInstallPage || isErrorPage || isFinishedPage;
		prevButton.setEnabled(!isSpecialPage && currentPage > 0);
		nextButton.setEnabled(!isSpecialPage && currentPage < document.getNumPages() - 1);
		
		boolean isReadyPage = page.getPageName().equals("ready");
		nextButton.setText(isReadyPage ? INSTALL_BUTTON_TEXT : NEXT_BUTTON_TEXT);
	}
	
	private void onStartInstallation() {
		System.out.println("Starting installation...");
		
		InstallPanel p = wizardPagePanels.get(currentPage).asInstallPanel();
		
		// This is hard-coded for AWS at the moment.
		// Eventually we will support other cloud providers.
		final AWSCloudService aws = new AWSCloudService();
		aws.setDocument(document);
		
		// Create the data directory
		aws.createDataDir();
		
		// Save Document in a properties file
		saveConfiguration(aws);

		// The InstallationProgress object orchestrates the installation
		// process and notifies observers (i.e., the InstallPanel) of
		// significant state changes
		final InstallationProgress<AWSInfo, AWSCloudService> progress = new InstallationProgress<AWSInfo, AWSCloudService>();
		aws.addInstallSteps(progress);
		progress.addInstallStep(new BootstrapStep<AWSInfo, AWSCloudService>(aws));
		p.setProgress(progress);
		
		// Listen for completion events
		progress.addObserver(new Observer() {
			@Override
			public void update(Observable o, Object arg) {
				if (progress.isFinished()) {
					onFinished();
				} else if (progress.isFatalException()) {
					onFatalException();
				}
			}
		});
		
		// Start a thread to run the installation.
		// We will create it as a daemon thread, trusting that
		// it will eventually reach a state where the UI will know
		// to continue (either because the installation succeeded
		// or because a fatal exception occurred.)
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				progress.executeAll(aws);
			}
		});
		t.setDaemon(true);
		t.start();
	}

	private void saveConfiguration(final AWSCloudService aws) {
		try {
			try (PrintWriter w = new PrintWriter(new FileWriter(new File(aws.getInfo().getDataDir(), "ccinstall.properties")))) {
				w.println("# CloudCoder installation wizard saved configuration properties");
				for (int i = 0; i < aws.getDocument().getNumPages(); i++) {
					Page page = aws.getDocument().get(i);
					for (IValue value : page) {
						if (!(value instanceof ImmutableStringValue)) {
							w.printf("%s.%s=%s\n", page.getPageName(), value.getName(), value.getObject().toString());
						}
					}
				}
			}
		} catch (IOException e) {
			// Should this be fatal?
			System.err.println("Error saving installer configuration to file");
			e.printStackTrace();
		}
	}
	
	private void onFinished() {
		LogPanel.getInstance().flushLog();
		goToPage("finished");
	}
	
	private void onFatalException() {
		LogPanel.getInstance().flushLog();
		goToPage("error");
	}

	private void goToPage(String pageName) {
		for (int i = 0; i < document.getNumPages(); i++) {
			Page p = document.get(i);
			if (p.getPageName().equals(pageName)) {
				currentPage = i;
				changePage();
				return;
			}
		}
		throw new IllegalArgumentException("No such page: " + pageName);
	}
}

package org.lightwolf.plugin;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.wizards.IClasspathContainerPage;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

public class LightWolfContainerWizard extends WizardPage implements IClasspathContainerPage {

    public LightWolfContainerWizard() {
        super("Lightwolf Library", "Lightwolf Library", null);
    }

    public boolean finish() {
        return true;
    }

    public IClasspathEntry getSelection() {
        return JavaCore.newContainerEntry(LightWolfContainerInitializer.LIGHTWOLF_CONTAINER_PATH);
    }

    public void setSelection(IClasspathEntry containerEntry) {
    // Ignore.
    }

    public void createControl(Composite parent) {
        Composite control = new Composite(parent, SWT.NONE);
        control.setLayout(new GridLayout(1, true));
        Label label = new Label(control, SWT.NONE);
        label.setText("The Lightwolf Library currently has no properties.");
        label.setLayoutData(new GridData(GridData.FILL));
        setControl(control);
    }

}

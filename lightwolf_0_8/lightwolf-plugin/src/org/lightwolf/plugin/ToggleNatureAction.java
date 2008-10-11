package org.lightwolf.plugin;

import java.util.Iterator;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;
import org.lightwolf.tools.LightWolfLog;

public class ToggleNatureAction implements IObjectActionDelegate {

    private ISelection selection;
    private IWorkbenchPart part;

    public void run(IAction action) {
        if (selection instanceof IStructuredSelection) {
            for (Iterator it = ((IStructuredSelection) selection).iterator(); it.hasNext();) {
                Object element = it.next();
                IProject project = null;
                if (element instanceof IProject) {
                    project = (IProject) element;
                } else if (element instanceof IAdaptable) {
                    project = (IProject) ((IAdaptable) element).getAdapter(IProject.class);
                }
                if (project != null) {
                    String msg;
                    try {
                        if (toggleNature(project)) {
                            msg = "Light Wolf nature added to project " + project.getName() + '.';
                        } else {
                            msg = "Light Wolf nature removed from project " + project.getName() + '.';
                        }
                    } catch (CoreException e) {
                        msg = "An error ocurred: [" + e.getMessage() + "]. Please check error log.";
                        e.printStackTrace();
                    }
                    if (part != null && part.getSite() != null) {
                        MessageDialog.openInformation(part.getSite().getShell(), "Light Wolf", msg);
                    } else {
                        LightWolfLog.print(msg);
                    }
                }
            }
        }
    }

    public void selectionChanged(IAction action, ISelection selection) {
        this.selection = selection;
    }

    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
        part = targetPart;
    }

    private boolean toggleNature(IProject project) throws CoreException {
        IProjectDescription description = project.getDescription();
        String[] natures = description.getNatureIds();

        for (int i = 0; i < natures.length; ++i) {
            if (LightWolfNature.NATURE_ID.equals(natures[i])) {
                // Remove the nature
                String[] newNatures = new String[natures.length - 1];
                System.arraycopy(natures, 0, newNatures, 0, i);
                System.arraycopy(natures, i + 1, newNatures, i, natures.length - i - 1);
                description.setNatureIds(newNatures);
                project.setDescription(description, null);
                return false;
            }
        }

        // Add the nature
        String[] newNatures = new String[natures.length + 1];
        System.arraycopy(natures, 0, newNatures, 0, natures.length);
        newNatures[natures.length] = LightWolfNature.NATURE_ID;
        description.setNatureIds(newNatures);
        project.setDescription(description, null);

        IJavaProject javaProject = JavaCore.create(project);
        IClasspathEntry[] rawClasspath = javaProject.getRawClasspath();
        boolean add = true;
        for (IClasspathEntry entry : rawClasspath) {
            if (entry.getEntryKind() != IClasspathEntry.CPE_CONTAINER) {
                continue;
            }
            IPath path = entry.getPath();
            if (path.equals(LightWolfContainerInitializer.LIGHTWOLF_CONTAINER_PATH)) {
                add = false;
                break;
            }
        }
        if (add) {
            IClasspathEntry[] newRawClasspath = new IClasspathEntry[rawClasspath.length + 1];
            System.arraycopy(rawClasspath, 0, newRawClasspath, 0, rawClasspath.length);
            newRawClasspath[rawClasspath.length] = JavaCore.newContainerEntry(LightWolfContainerInitializer.LIGHTWOLF_CONTAINER_PATH);
            javaProject.setRawClasspath(newRawClasspath, true, null);
        }
        return true;
    }

}

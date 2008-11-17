package org.lightwolf.plugin;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ClasspathContainerInitializer;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.lightwolf.tools.LightWolfLog;
import org.osgi.framework.Bundle;

public class LightWolfContainerInitializer extends ClasspathContainerInitializer {

    public static final String LIGHTWOLF_CONTAINER_ID = "org.lightwolf.plugin.LIGHTWOLF_CONTAINER";
    public static final IPath LIGHTWOLF_CONTAINER_PATH = new Path(LIGHTWOLF_CONTAINER_ID + "/default");

    public LightWolfContainerInitializer() {}

    @Override
    public void initialize(IPath containerPath, IJavaProject project) throws CoreException {
        LightWolfLog.printf("Called %s, %s.\n", containerPath.toPortableString(), project.getProject().getName());
        if (containerPath.equals(LIGHTWOLF_CONTAINER_PATH)) {
            IClasspathContainer[] classpathContainer = new IClasspathContainer[] { createContainer() };
            JavaCore.setClasspathContainer(containerPath, new IJavaProject[] { project }, classpathContainer, null);
        }
    }

    private IClasspathContainer createContainer() throws CoreException {
        Bundle bundle = LightWolfActivator.getDefault().getBundle();
        IPath path = getPath(bundle, "lib/lightwolf.jar");
        IPath sourcePath = getPath(bundle, "src.zip");
        final IClasspathEntry cpEntry = JavaCore.newLibraryEntry(path, sourcePath, null);
        return new IClasspathContainer() {

            public IClasspathEntry[] getClasspathEntries() {
                return new IClasspathEntry[] { cpEntry };
            }

            public String getDescription() {
                return "Lightwolf Library";
            }

            public int getKind() {
                return K_APPLICATION;
            }

            public IPath getPath() {
                return LIGHTWOLF_CONTAINER_PATH;
            }
        };
    }

    private static IPath getPath(Bundle bundle, String entry) throws CoreException {
        URL url;
        try {
            url = bundle.getEntry(entry);
            if (url == null) {
                throw new CoreException(new Status(IStatus.ERROR, LightWolfActivator.PLUGIN_ID, "Could not find " + entry + " on bundle " + bundle.getSymbolicName() + '.'));
            }
            url = FileLocator.toFileURL(url);
            if (url == null) {
                throw new CoreException(new Status(IStatus.ERROR, LightWolfActivator.PLUGIN_ID, "Could not find file " + entry + " on bundle " + bundle.getSymbolicName() + '.'));
            }
            try {
                File file = new File(url.toURI());
                IPath path = new Path(file.getCanonicalPath());
                return path;
            } catch (URISyntaxException e) {
                throw new CoreException(new Status(IStatus.ERROR, LightWolfActivator.PLUGIN_ID, "Error resolving URL " + url.toString() + ".", e));
            }
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR, LightWolfActivator.PLUGIN_ID, "Error getting file " + entry + ".", e));
        }
    }

}

package org.lightwolf.builder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.lightwolf.plugin.LightWolfActivator;
import org.lightwolf.tools.ClassLoaderProvider;
import org.lightwolf.tools.IClassProvider;
import org.lightwolf.tools.IClassResource;
import org.lightwolf.tools.LightWolfEnhancer;
import org.lightwolf.tools.LightWolfLog;
import org.lightwolf.tools.PublicByteArrayOutputStream;

public class LightWolfBuilder extends IncrementalProjectBuilder {

    public static final String BUILDER_ID = "org.lightwolf.nature.lightwolfBuilder";

    private IJavaProject javaProject;
    private ArrayList<IPath> outputs;
    private File origContDir;
    private LightWolfEnhancer enhancer;

    @Override
    protected IProject[] build(int kind, //
            @SuppressWarnings("unchecked")
            Map args, //
            IProgressMonitor monitor) throws CoreException {

        IProject project = getProject();
        if (project == null) {
            LightWolfLog.println("Project is null.");
            return null;
        }

        String projectName = project.getName();
        LightWolfLog.printf("Building project %s...\n", projectName);

        IPath path = project.getWorkingLocation(LightWolfActivator.PLUGIN_ID);
        if (path == null) {
            LightWolfLog.printf("Project %s working location for %s is null.\n", projectName, LightWolfActivator.PLUGIN_ID);
            return null;
        }

        try {

            javaProject = JavaCore.create(project);
            if (javaProject == null) {
                LightWolfLog.printf("%s is not a Java project.\n", projectName);
                return null;
            }

            outputs = new ArrayList<IPath>(4);
            try {
                IPath defaultOutput = javaProject.getOutputLocation();
                if (defaultOutput != null) {
                    outputs.add(defaultOutput.removeFirstSegments(1));
                }
            } catch (JavaModelException e) {
                // Bizarre API - throws exception instead of returning null. Check JavaDoc.
            }
            IClasspathEntry[] classpath = javaProject.getRawClasspath();
            for (IClasspathEntry cpEntry : classpath) {
                if (cpEntry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
                    continue;
                }
                IPath output = cpEntry.getOutputLocation();
                if (output != null) {
                    outputs.add(output.removeFirstSegments(1));
                }
            }
            if (outputs.isEmpty()) {
                LightWolfLog.printf("Couldn't find output folders in project %s.\n", projectName);
                return null;
            }
            LightWolfLog.printf("Listing output folders for project %s:\n", projectName);
            for (IPath output : outputs) {
                LightWolfLog.printf("   %s\n", output.toString());
            }

            IPath pluginDirPath = project.getWorkingLocation(LightWolfActivator.PLUGIN_ID);
            origContDir = pluginDirPath == null ? null : new File(pluginDirPath.toFile(), "original_contents");
            if (!origContDir.exists()) {
                origContDir.mkdirs();
                if (!origContDir.exists()) {
                    origContDir = null;
                }
            }

            enhancer = new LightWolfEnhancer(new ClassProvider());
            project.accept(new ResourceVisitor());

            //            if (kind == FULL_BUILD) {
            //                project.accept(new ResourceVisitor());
            //            } else {
            //                IResourceDelta delta = getDelta(project);
            //                if (delta == null) {
            //                    project.accept(new ResourceVisitor());
            //                } else {
            //                    delta.accept(new ResourceDeltaVisitor());
            //                }
            //            }
        } finally {
            outputs = null;
            javaProject = null;
            enhancer = null;
        }
        return null;
    }

    void processResource(IResource resource) throws CoreException {
        if (!(resource instanceof IFile)) {
            return;
        }
        if (!resource.getName().toLowerCase().endsWith(".class")) {
            return;
        }
        IFile file = (IFile) resource;
        IPath path = file.getProjectRelativePath();
        boolean isOutput = false;
        for (IPath output : outputs) {
            if (output.isPrefixOf(path)) {
                isOutput = true;
                break;
            }
        }
        if (!isOutput) {
            return;
        }
        try {
            PublicByteArrayOutputStream oldContents = new PublicByteArrayOutputStream();
            InputStream contents = file.getContents();
            try {
                IOUtils.copy(contents, oldContents);
                contents.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            PublicByteArrayOutputStream newContents = oldContents.clone();

            int result = enhancer.transform(newContents);
            switch (result) {
                case LightWolfEnhancer.TRANSFORMED:
                    setOldContents(file, oldContents);
                    break;
                case LightWolfEnhancer.DONT_NEED_TRANSFORM:
                    setOldContents(file, null);
                    return;
                case LightWolfEnhancer.WAS_TRANSFORMED_BEFORE:
                    PublicByteArrayOutputStream origContents = getOldContents(file);
                    if (origContents == null) {
                        throw new IllegalStateException("Could not find original contents of file " + file.getFullPath().toPortableString() + ". Cleaning this project might solve this problem.");
                    }
                    newContents = origContents;
                    result = enhancer.transform(newContents);
                    switch (result) {
                        case LightWolfEnhancer.TRANSFORMED:
                            break;
                        case LightWolfEnhancer.DONT_NEED_TRANSFORM:
                            setOldContents(file, null);
                            break;
                        default:
                            throw new IllegalStateException("Bad transformation result for original contents of file " + file.getFullPath().toPortableString() + ": " + LightWolfEnhancer.getResultName(result));
                    }
                    break;
                default:
                    throw new IllegalStateException("Bad transformation result for file " + file.getFullPath().toPortableString() + ": " + LightWolfEnhancer.getResultName(result));

            }

            if (!Arrays.equals(oldContents.getBuffer(), newContents.getBuffer())) {
                ByteArrayInputStream bais = new ByteArrayInputStream(newContents.getBuffer(), 0, newContents.size());
                file.setContents(bais, false, false, null);
                LightWolfLog.printf("Class %s successfully enhanced.\n", file.getFullPath());
            }

        } catch (Throwable e) {
            LightWolfLog.printf("Error enhancing class %s...\n", file.getFullPath());
            LightWolfLog.printTrace(e);
            if (e instanceof Error) {
                throw (Error) e;
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            if (e instanceof CoreException) {
                throw (CoreException) e;
            }
            throw new RuntimeException(e);
        }
    }

    private PublicByteArrayOutputStream getOldContents(IFile file) throws IOException {
        File origCont = new File(origContDir, file.getProjectRelativePath().toString());
        if (!origCont.exists()) {
            return null;
        }
        PublicByteArrayOutputStream ret = new PublicByteArrayOutputStream();
        InputStream contents = new FileInputStream(origCont);
        IOUtils.copy(contents, ret);
        contents.close();
        return ret;
    }

    private void setOldContents(IFile file, PublicByteArrayOutputStream oldContents) throws IOException {
        File origCont = new File(origContDir, file.getProjectRelativePath().toString());
        if (oldContents == null) {
            origCont.delete();
            return;
        }
        File parent = origCont.getParentFile();
        parent.mkdirs();
        OutputStream contents = new FileOutputStream(origCont);
        try {
            contents.write(oldContents.getBuffer());
        } finally {
            contents.close();
        }
    }

    class ClassProvider implements IClassProvider {

        public IClassResource getClass(String resName) throws IOException {
            String javaName = null;
            if (resName.indexOf('$') != -1) {
                int barPos = resName.lastIndexOf('/');
                String className = resName.substring(barPos + 1, resName.length() - ".class".length());
                int dolarPos = className.indexOf('$');
                if (dolarPos != -1) {
                    className = className.substring(0, dolarPos);
                    String pkgName = resName.substring(0, barPos + 1);
                    javaName = pkgName + className + ".java";
                }
            }
            IJavaElement element;
            if (javaName != null) {
                try {
                    element = javaProject.findElement(Path.fromPortableString(javaName));
                } catch (JavaModelException e) {
                    e.printStackTrace();
                    return null;
                }
                if (element == null) {
                    LightWolfLog.printf("Could not find element for %s.\n", javaName);
                    return null;
                }
            } else {
                try {
                    element = javaProject.findElement(Path.fromPortableString(resName));
                } catch (JavaModelException e) {
                    e.printStackTrace();
                    return null;
                }
                if (element == null) {
                    LightWolfLog.printf("Could not find element for %s.\n", resName);
                    return null;
                }
                if (element instanceof IClassFile) {
                    return fromClassFile((IClassFile) element, resName);
                }
            }
            if (element instanceof ICompilationUnit) {
                return fromCompilationUnit((ICompilationUnit) element, resName);
            }
            LightWolfLog.printf("Element for %s is of unknown class: %s.\n", resName, element.getClass().getName());
            return null;
        }

        private IClassResource fromClassFile(IClassFile classFile, String resName) throws IOException {
            byte[] bytes;
            try {
                bytes = classFile.getBytes();
            } catch (JavaModelException e) {
                e.printStackTrace();
                return null;
            }
            return fromInputStream(new ByteArrayInputStream(bytes));
        }

        private IClassResource fromCompilationUnit(ICompilationUnit compilationUnit, String resName) throws IOException {
            IPath path = compilationUnit.getPath();
            IJavaProject javaProject = compilationUnit.getJavaProject();
            IPath outputFolder = null;
            IClasspathEntry[] classpath;
            try {
                classpath = javaProject.getRawClasspath();
            } catch (JavaModelException e) {
                e.printStackTrace();
                return null;
            }
            for (IClasspathEntry entry : classpath) {
                if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
                    continue;
                }
                IPath srcPath = entry.getPath();
                if (srcPath.isPrefixOf(path)) {
                    outputFolder = entry.getOutputLocation();
                    if (outputFolder == null) {
                        try {
                            outputFolder = javaProject.getOutputLocation();
                        } catch (JavaModelException e) {
                            e.printStackTrace();
                            return null;
                        }
                    }
                    outputFolder = outputFolder.removeFirstSegments(1);
                    break;
                }
            }
            if (outputFolder == null) {
                LightWolfLog.println("Could not find output folder for file " + path + ".");
                return null;
            }
            IPath outputFile = outputFolder.append(resName);
            IFile file = javaProject.getProject().getFile(outputFile);
            return fromFile(file);
        }

        private IClassResource fromFile(IFile file) throws IOException {
            InputStream is;
            try {
                is = file.getContents();
            } catch (CoreException e) {
                e.printStackTrace();
                return null;
            }
            return fromInputStream(is);
        }

        private IClassResource fromInputStream(InputStream is) throws IOException {
            try {
                return ClassLoaderProvider.makeClassResource(is);
            } finally {
                is.close();
            }
        }

    }

    class ResourceVisitor implements IResourceVisitor {

        public boolean visit(IResource resource) throws CoreException {
            processResource(resource);
            return true;
        }
    }

    class ResourceDeltaVisitor implements IResourceDeltaVisitor {

        public boolean visit(IResourceDelta delta) throws CoreException {
            IResource resource = delta.getResource();
            switch (delta.getKind()) {
                case IResourceDelta.ADDED:
                case IResourceDelta.CHANGED:
                    processResource(resource);
                    break;
                case IResourceDelta.REMOVED:
                    break;
                default:
                    throw new AssertionError("Unknown delta kind: " + delta.getKind());
            }
            return true;
        }
    }

}

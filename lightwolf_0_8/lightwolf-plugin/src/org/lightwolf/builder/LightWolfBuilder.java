package org.lightwolf.builder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
import org.lightwolf.tools.ClassLoaderProvider;
import org.lightwolf.tools.IClassProvider;
import org.lightwolf.tools.IClassResource;
import org.lightwolf.tools.LightWolfEnhancer;
import org.lightwolf.tools.LightWolfLog;
import org.lightwolf.tools.PublicByteArrayOutputStream;

public class LightWolfBuilder extends IncrementalProjectBuilder {

    public static final String BUILDER_ID = "org.lightwolf.nature.lightwolfBuilder";

    private ArrayList<IPath> outputs;
    private IJavaProject project;
    private LightWolfEnhancer enhancer;

    protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
        LightWolfLog.printf("Building project %s...\n", getProject().getName());
        IJavaProject project = JavaCore.create(getProject());
        if (project == null) {
            LightWolfLog.println("Not a Java project.");
            return null;
        }
        ArrayList<IPath> outputs = new ArrayList<IPath>(4);
        try {
            IPath defaultOutput = project.getOutputLocation();
            if (defaultOutput != null) {
                // Who knows if it will return null?
                outputs.add(defaultOutput.removeFirstSegments(1));
            }
        } catch (JavaModelException e) {
            // Bizarre API - throws exception instead of returning null. Check JavaDoc.
        }
        IClasspathEntry[] classpath = project.getRawClasspath();
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
            LightWolfLog.printf("Couldn't find output folders in project %s.\n", getProject().getName());
            return null;
        }
        this.outputs = outputs;
        this.project = project;
        enhancer = new LightWolfEnhancer(new ClassProvider());
        LightWolfLog.printf("Listing output folders for project %s:\n", getProject().getName());
        for (IPath output : outputs) {
            LightWolfLog.printf("   %s\n", output.toString());
        }
        if (kind == FULL_BUILD) {
            fullBuild(monitor);
        } else {
            IResourceDelta delta = getDelta(getProject());
            if (delta == null) {
                fullBuild(monitor);
            } else {
                incrementalBuild(delta, monitor);
            }
        }
        return null;
    }

    private void fullBuild(final IProgressMonitor monitor) throws CoreException {
        getProject().accept(new ResourceVisitor());
    }

    private void incrementalBuild(IResourceDelta delta, IProgressMonitor monitor) throws CoreException {
        // the visitor does the work.
        delta.accept(new ResourceDeltaVisitor());
    }

    void processResource(IResource resource) throws CoreException {
        if (!(resource instanceof IFile)) {
            return;
        }
        if (!resource.getName().endsWith(".class")) {
            // TODO: Is it possible a class file ends with upper-case letters (.CLASS or .ClAsS)?
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
            PublicByteArrayOutputStream pbaos = new PublicByteArrayOutputStream();
            InputStream contents = file.getContents();
            try {
                IOUtils.copy(contents, pbaos);
                contents.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (enhancer.transform(pbaos)) {
                LightWolfLog.printf("Enhancing class %s...\n", file.getName());
                ByteArrayInputStream bais = new ByteArrayInputStream(pbaos.getBuffer(), 0, pbaos.size());
                file.setContents(bais, false, false, null);
                LightWolfLog.printf("Class %s sucessfully enhanced.\n", file.getName());
            }
        } catch (Throwable e) {
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
            throw new AssertionError(e);
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
                    element = project.findElement(Path.fromPortableString(javaName));
                } catch (JavaModelException e) {
                    e.printStackTrace();
                    return null;
                }
                if (element == null) {
                    LightWolfLog.println("Could not find element for " + javaName);
                    return null;
                }
            } else {
                try {
                    element = project.findElement(Path.fromPortableString(resName));
                } catch (JavaModelException e) {
                    e.printStackTrace();
                    return null;
                }
                if (element == null) {
                    LightWolfLog.println("Could not find element for " + resName);
                    return null;
                }
                if (element instanceof IClassFile) {
                    return fromClassFile((IClassFile) element, resName);
                }
            }
            if (element instanceof ICompilationUnit) {
                return fromCompilationUnit((ICompilationUnit) element, resName);
            }
            LightWolfLog.printf("Element for " + resName + " is of unknown class: %s.\n", element.getClass().getName());
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
            project = compilationUnit.getJavaProject();
            IPath outputFolder = null;
            IClasspathEntry[] classpath;
            try {
                classpath = project.getRawClasspath();
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
                            outputFolder = project.getOutputLocation();
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
            IFile file = project.getProject().getFile(outputFile);
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

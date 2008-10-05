package org.lightwolf.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.EmptyVisitor;

public class ClassLoaderProvider implements IClassProvider {

    private final ClassLoader classLoader;

    public ClassLoaderProvider(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public IClassResource getClass(String resName) throws IOException {
        InputStream is = classLoader.getResourceAsStream(resName);
        if (is == null) {
            return null;
        }
        try {
            return makeClassResource(is);
        } finally {
            is.close();
        }
    }

    public static IClassResource makeClassResource(InputStream is) throws IOException {
        ClassReader reader = new ClassReader(is);
        final ClassResource ret = new ClassResource();
        final ArrayList<IMethod> methods = new ArrayList<IMethod>();
        ClassVisitor classVisitor = new EmptyVisitor() {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                ret.superName = superName;
                ret.interfaces = interfaces == null ? IClassResource.NO_INTERFACES : interfaces;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                final Method method = new Method();
                method.name = name;
                method.desc = desc;
                method.annotations = Collections.EMPTY_LIST;
                methods.add(method);
                return new EmptyVisitor() {

                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (method.annotations == Collections.EMPTY_LIST) {
                            method.annotations = new ArrayList<String>();
                        }
                        int len = desc.length();
                        if (desc.charAt(0) != 'L' || desc.charAt(len - 1) != ';') {
                            return null;
                        }
                        method.annotations.add(desc.substring(1, len - 1));
                        return null;
                    }
                };
            }
        };
        reader.accept(classVisitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (methods.isEmpty()) {
            ret.methods = IClassResource.NO_METHODS;
        } else {
            ret.methods = new IMethod[methods.size()];
            methods.toArray(ret.methods);
        }
        return ret;
    }

    private static class ClassResource implements IClassResource {

        String superName;
        String[] interfaces;
        IMethod[] methods;

        public String getSuperName() {
            return superName;
        }

        public String[] getInterfaces() {
            return interfaces;
        }

        public IMethod[] getMethods() {
            return methods;
        }

    }

    private static class Method implements IMethod {

        String name;
        String desc;
        List<String> annotations;

        public String getName() {
            return name;
        }

        public String getDescriptor() {
            return desc;
        }

        public boolean containsAnnotation(String annot) {
            return annotations.contains(annot);
        }

    }

}

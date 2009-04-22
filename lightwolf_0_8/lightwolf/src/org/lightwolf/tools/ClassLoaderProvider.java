/*
 * Copyright (c) 2007, Fernando Colombo. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1) Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * 2) Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED ''AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.lightwolf.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.EmptyVisitor;

public class ClassLoaderProvider implements IClassProvider {

    private final ClassLoader classLoader;

    public ClassLoaderProvider(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public IClassResource getClass(String resName) throws IOException {
        URL url = classLoader.getResource(resName);
        if (url == null) {
            return null;
        }
        InputStream is = url.openStream();
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
                ret.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                final Method method = new Method();
                method.name = name;
                method.desc = desc;
                method.annotations = Collections.emptyList();
                methods.add(method);
                return new EmptyVisitor() {

                    @Override
                    public AnnotationVisitor visitAnnotation(String _desc, boolean visible) {
                        if (method.annotations == Collections.EMPTY_LIST) {
                            method.annotations = new ArrayList<String>();
                        }
                        int len = _desc.length();
                        if (_desc.charAt(0) != 'L' || _desc.charAt(len - 1) != ';') {
                            return null;
                        }
                        method.annotations.add(_desc.substring(1, len - 1));
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
        boolean isInterface;

        public String getSuperName() {
            return superName;
        }

        public String[] getInterfaces() {
            return interfaces;
        }

        public IMethod[] getMethods() {
            return methods;
        }

        public boolean isInterface() {
            return isInterface;
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

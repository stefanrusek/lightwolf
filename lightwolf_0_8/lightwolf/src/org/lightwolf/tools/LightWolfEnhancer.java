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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.lightwolf.FlowMethod;
import org.lightwolf.MethodFrame;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.EmptyVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.util.AbstractVisitor;

public class LightWolfEnhancer {

    public static final int DONT_NEED_TRANSFORM = 0;
    public static final int NEED_TRANSFORM = 1;
    public static final int WAS_TRANSFORMED_BEFORE = 2;
    public static final int TRANSFORMED = 3;

    private static final Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");
    private static final String FRAME_CLASS = MethodFrame.class.getName().replace('.', '/').intern();
    private static final String FLOWMETHOD_ANNOT = FlowMethod.class.getName().replace('.', '/').intern();
    private static final String LIGHTWOLF_ENHANCED = "LightWolfEnhanced";

    // TODO: Field for testing purposes; move from here when done.
    public static boolean changeFile = true;

    public static String getResultName(int result) {
        switch (result) {
            case DONT_NEED_TRANSFORM:
                return "DONT_NEED_TRANSFORM";
            case NEED_TRANSFORM:
                return "NEED_TRANSFORM";
            case WAS_TRANSFORMED_BEFORE:
                return "WAS_TRANSFORMED_BEFORE";
            case TRANSFORMED:
                return "TRANSFORMED";
            default:
                throw new IllegalStateException("Unknown result code: " + result);
        }
    }

    private static int getTransformState(ClassReader reader) {
        final int requires[] = new int[] { DONT_NEED_TRANSFORM };
        ClassVisitor classVisitor = new EmptyVisitor() {

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (requires[0] != DONT_NEED_TRANSFORM) {
                    return null;
                }
                return new EmptyVisitor() {

                    @Override
                    public AnnotationVisitor visitAnnotation(String _desc, boolean visible) {
                        if (_desc.equals("L" + FLOWMETHOD_ANNOT + ";")) {
                            final boolean[] isManual = new boolean[] { false };
                            return new EmptyVisitor() {

                                @Override
                                public void visit(String _name, Object value) {
                                    if (_name.equals("manual") && value.equals(Boolean.TRUE)) {
                                        isManual[0] = true;
                                    }
                                }

                                @Override
                                public void visitEnd() {
                                    if (!isManual[0]) {
                                        requires[0] = NEED_TRANSFORM;
                                    }
                                }
                            };
                        }
                        return null;
                    }
                };
            }

            @Override
            public void visitAttribute(Attribute attr) {
                if (attr.type.equals(LIGHTWOLF_ENHANCED)) {
                    requires[0] = WAS_TRANSFORMED_BEFORE;
                }
            }
        };
        reader.accept(classVisitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return requires[0];
    }

    private final IClassProvider classProvider;
    private final HashMap<MethodKey, Boolean> methodCache = new HashMap<MethodKey, Boolean>();
    private final HashMap<String, IClassResource> classCache = new HashMap<String, IClassResource>();
    private final HashMap<String, String> knownClasses = new HashMap<String, String>();

    public LightWolfEnhancer(IClassProvider classProvider) {
        this.classProvider = classProvider;
    }

    public LightWolfEnhancer(ClassLoader classLoader) {
        classProvider = new ClassLoaderProvider(classLoader);
    }

    public int transform(PublicByteArrayOutputStream classBytes) {
        ClassNode clazz = new ClassNode();
        ClassVisitor cv = clazz;
        ClassReader reader = new ClassReader(classBytes.getBuffer(), 0, classBytes.size());
        int result = getTransformState(reader);
        if (result != NEED_TRANSFORM) {
            return result;
        }
        reader.accept(cv, 0);
        boolean changed = false;
        List<MethodNode> methods = clazz.methods;
        Analyzer analyzer = new Analyzer(new Verifier());
        for (MethodNode method : methods) {
            if (isFlowMethod(method)) {
                try {
                    transform(clazz, analyzer, method);
                } catch (AnalyzerException e) {
                    throw new RuntimeException(e);
                }
                changed = true;
            }
        }
        assert changed;
        if (clazz.attrs == null) {
            clazz.attrs = new ArrayList<Object>(1);
        }
        clazz.attrs.add(new LightWolfEnhancedAttribute());
        ClassWriter writer = new ClassWriter(0);
        clazz.accept(writer);
        classBytes.reset();
        try {
            classBytes.write(writer.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return TRANSFORMED;
    }

    public int transform(File f) throws IOException {
        PublicByteArrayOutputStream pbaos;
        FileInputStream fis = new FileInputStream(f);
        try {
            pbaos = new PublicByteArrayOutputStream();
            IOUtils.copy(fis, pbaos);
        } finally {
            fis.close();
        }
        int result = transform(pbaos);
        if (result != TRANSFORMED) {
            return result;
        }
        if (changeFile) {
            FileOutputStream fos = new FileOutputStream(f);
            try {
                PublicByteArrayInputStream pbais = new PublicByteArrayInputStream(pbaos.getBuffer(), 0, pbaos.size());
                IOUtils.copy(pbais, fos);
            } finally {
                fos.close();
            }
        }
        return result;
    }

    private static boolean isFlowMethod(MethodNode method) {
        if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
            return false;
        }
        if (method.name.charAt(0) == '<') {
            return false;
        }
        return containsFlowMethodAnnot(method.invisibleAnnotations) || containsFlowMethodAnnot(method.visibleAnnotations);
    }

    private static boolean containsFlowMethodAnnot(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annot : annotations) {
            if (annot.desc.equals("L" + FLOWMETHOD_ANNOT + ";")) {
                List<Object> values = annot.values;
                if (values != null) {
                    for (int i = 0; i < values.size(); i += 2) {
                        String name = (String) values.get(i);
                        if (name.equals("manual")) {
                            Boolean value = (Boolean) values.get(i + 1);
                            if (value) {
                                return false;
                            }
                            break;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    private void transform(ClassNode clazz, Analyzer analyzer, MethodNode method) throws AnalyzerException {

        if (!addManual(method.invisibleAnnotations) && !addManual(method.visibleAnnotations)) {
            throw new AssertionError();
        }

        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int frameVar = method.maxLocals;
        InsnList insts = method.instructions;
        Frame[] frames = analyzer.analyze(clazz.name, method);
        ArrayList<ResumeInfo> gotos = new ArrayList<ResumeInfo>();
        int invId = 0;
        AbstractInsnNode last = insts.getLast();

        // Build a catch block in the method end, so we can track when the method exits with exception.

        LabelNode catchBlock = new LabelNode();
        insts.add(catchBlock);
        insts.add(new InsnNode(Opcodes.DUP));
        insts.add(new VarInsnNode(Opcodes.ALOAD, frameVar));
        insts.add(new InsnNode(Opcodes.SWAP));
        insts.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "exit", "(Ljava/lang/Throwable;)V"));
        insts.add(new InsnNode(Opcodes.ATHROW));

        // After the catch block we put a suffix were we track normal exit conditions.
        // Every xRETURN in this method will be replaced by a GOTO this suffix. 

        LabelNode returnPoint = new LabelNode();
        insts.add(returnPoint);
        insts.add(new VarInsnNode(Opcodes.ALOAD, frameVar));
        insts.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "exit", "()V"));
        Type returnType = Type.getReturnType(method.desc);
        insts.add(new InsnNode(getReturnOpcode(returnType)));

        // Now we decorate the original method instructions, from the last to the first.

        int index = insts.indexOf(last);
        for (AbstractInsnNode cur = last; cur != null;) {
            AbstractInsnNode previous = cur.getPrevious();
            switch (cur.getOpcode()) {
                case Opcodes.RETURN: {
                    decorateExit(insts, cur, 0, getStackTypes(frames[index]), returnPoint);
                    break;
                }
                case Opcodes.ARETURN:
                case Opcodes.IRETURN:
                case Opcodes.FRETURN: {
                    decorateExit(insts, cur, 1, getStackTypes(frames[index]), returnPoint);
                    break;
                }
                case Opcodes.DRETURN:
                case Opcodes.LRETURN: {
                    decorateExit(insts, cur, 2, getStackTypes(frames[index]), returnPoint);
                    break;
                }
                case Opcodes.MONITORENTER: {
                    decorateMonitorEnter(insts, cur, frameVar);
                    break;
                }
                case Opcodes.MONITOREXIT: {
                    decorateMonitorExit(insts, cur, frameVar);
                    break;
                }
                case Opcodes.INVOKESTATIC:
                case Opcodes.INVOKEVIRTUAL:
                case Opcodes.INVOKEINTERFACE:
                case Opcodes.INVOKESPECIAL: {
                    MethodInsnNode ins = (MethodInsnNode) cur;
                    if (!isFlowMethod(ins)) {
                        break;
                    }
                    Type[] vars = getVarTypes(frames[index], isStatic);
                    Type[] stackBefore = getStackTypes(frames[index]);
                    Type[] stackAfter = getStackTypes(frames[index + 1]);
                    ResumeInfo ri = decorateInvocation(method, insts, cur, frameVar, vars, stackBefore, stackAfter, ++invId, returnPoint);
                    gotos.add(ri);
                    break;
                }
            }
            cur = previous;
            --index;
        }

        method.maxLocals++;
        method.maxStack += 100; // TODO: That's too much; find a way to optimize it.

        // Now we get the start and end labels.

        LabelNode start;

        AbstractInsnNode first = insts.getFirst();
        if (first instanceof LabelNode) {
            start = (LabelNode) first;
        } else {
            start = new LabelNode();
            insts.insert(start);
        }

        // Decorate method entry-point with a call to <FRAME_CLASS>.enter().

        if (isStatic) {
            insts.insertBefore(start, new LdcInsnNode(Type.getObjectType(clazz.name)));
        } else {
            insts.insertBefore(start, new VarInsnNode(Opcodes.ALOAD, 0));
        }
        insts.insertBefore(start, new LdcInsnNode(method.name));
        insts.insertBefore(start, new LdcInsnNode(method.desc));
        insts.insertBefore(start, new MethodInsnNode(Opcodes.INVOKESTATIC, FRAME_CLASS, "enter", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)L" + FRAME_CLASS + ";"));
        if (!gotos.isEmpty()) {
            insts.insertBefore(start, new InsnNode(Opcodes.DUP));
        }
        insts.insertBefore(start, new VarInsnNode(Opcodes.ASTORE, frameVar));
        if (!gotos.isEmpty()) {
            insts.insertBefore(start, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "resumePoint", "()I"));
        }

        // Build a switch table entry, so a resuming method can go directly to the one that caused suspension.

        AbstractInsnNode aftResPoint = start.getPrevious();
        LabelNode[] labels = new LabelNode[gotos.size()];

        for (ResumeInfo ri : gotos) {
            labels[ri.invocationId - 1] = new LabelNode();
            insts.insertBefore(start, labels[ri.invocationId - 1]);
            insts.insertBefore(start, new VarInsnNode(Opcodes.ALOAD, frameVar));
            pushShort(insts, start, ri.varCount);
            pushShort(insts, start, ri.objVarCount);
            insts.insertBefore(start, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "prepare", "(II)L" + FRAME_CLASS + ";"));
            // Restore variable values.
            for (int i = ri.vars.length - 1; i >= 0; --i) {
                if (ri.vars[i] != null) {
                    restoreVar(i, ri.vars[i], insts, start);
                }
            }
            if (ri.stackVarCount + ri.stackObjVarCount > 0) {
                // Prepare to restore stack values. The stack values are restored just before the invocation point.
                pushShort(insts, start, ri.varCount + ri.stackVarCount);
                pushShort(insts, start, ri.objVarCount + ri.stackObjVarCount);
                insts.insertBefore(start, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "prepare", "(II)L" + FRAME_CLASS + ";"));
            } else {
                // But if there is no stack value to restore, we must remove the useless frame object from the stack.
                insts.insertBefore(start, new InsnNode(Opcodes.POP));
            }
            // Goto the "restore stack values" point, or directly to the invocation.
            insts.insertBefore(start, new JumpInsnNode(Opcodes.GOTO, ri.befInvLabel));
        }

        if (!gotos.isEmpty()) {
            insts.insert(aftResPoint, new TableSwitchInsnNode(1, gotos.size(), start, labels));
        }

        List<TryCatchBlockNode> tryCatchBlocks = method.tryCatchBlocks;
        for (int i = 0; i < tryCatchBlocks.size(); ++i) {
            TryCatchBlockNode aTry = tryCatchBlocks.get(i);
            if (aTry.type == null && aTry.start == aTry.handler) {
                removeTryCatch(tryCatchBlocks, i);
                --i;
            }
        }

        TryCatchBlockNode tryCatch = new TryCatchBlockNode(start, catchBlock, catchBlock, null);
        method.tryCatchBlocks.add(tryCatch);

        try {
            frames = analyzer.analyze(clazz.name, method);
        } catch (Throwable e) {
            e.printStackTrace(System.out);
            printTrace(method, null, insts);
            if (e instanceof AnalyzerException) {
                throw (AnalyzerException) e;
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw (Error) e;
        }

        // printTrace(method, frames, insts);

    }

    /**
     * Yes, we are removing this try-finally block!!! This try-finally, where
     * start==handler, is certainly generated by the compiler, since the try{}
     * and finally{} are actually the same block. The problem with this block is
     * that any exception thrown by finally{} is handled by the block again,
     * which causes a new exception to be thrown in an endless loop. A block
     * like this is generated in the following code:
     * 
     * <pre>
     *   synchronized (x) {
     *        blah();
     *   }
     * </pre>
     * 
     * Eclipse compiler does this:
     * 
     * <pre>
     *    MONITORENTER x;
     *    try {
     *        blah();
     *        MONITOREXIT x;
     *        GOTO after;
     *    } catch-any {
     *        try-catch-any { // HERE IS THE BLOCK!!!
     *            MONITOREXIT x;
     *            throw any;
     *        }
     *    }
     *    after:
     *    ...
     * </pre>
     * 
     * We might enhance this block to this:
     * 
     * <pre>
     *    if continuing, GOTO resumepoint;
     *    MONITORENTER x;
     *    try {
     *        resumepoint:
     *        blah();
     *        if (leaving) RETURN;
     *        MONITOREXIT x;
     *        GOTO after;
     *    } catch-any {
     *        try-catch-any { // HERE IS THE BLOCK!!!
     *            MONITOREXIT x;
     *            throw any;
     *        }
     *    }
     *    after:
     *    ...
     * </pre>
     * 
     * This blah is leaving or resuming, this causes the RETURN or the first
     * MONITOREXIT x to throw IllegalMonitorState. The problem is that the
     * second MONITOREXIT x will also throw IllegalMonitorState, and hence we
     * are in an infinite loop. To solve this problem, we simply let the
     * exception go by removing the bizarre try-finally {} block.
     */
    private static void removeTryCatch(List<TryCatchBlockNode> tryCatchBlocks, int i) {
        tryCatchBlocks.remove(i);
    }

    private static boolean addManual(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annot : annotations) {
            if (annot.desc.equals("L" + FLOWMETHOD_ANNOT + ";")) {
                List<Object> values = annot.values;
                if (values != null) {
                    for (int i = 0; i < values.size(); i += 2) {
                        String name = (String) values.get(i);
                        if (name.equals("manual")) {
                            Boolean value = (Boolean) values.get(i + 1);
                            if (value) {
                                return false;
                            }
                            values.set(i + 1, Boolean.TRUE);
                            return true;
                        }
                    }
                } else {
                    values = new ArrayList<Object>(2);
                    annot.values = values;
                }
                values.add("manual");
                values.add(Boolean.TRUE);
                return true;
            }
        }
        return false;
    }

    private static void decorateMonitorEnter(InsnList insts, AbstractInsnNode monIns, int frameVar) {
        insts.insertBefore(monIns, new InsnNode(Opcodes.DUP));
        AbstractInsnNode after = monIns.getNext();
        insts.insertBefore(after, new VarInsnNode(Opcodes.ALOAD, frameVar));
        insts.insertBefore(after, new InsnNode(Opcodes.SWAP));
        insts.insertBefore(after, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "monitorEnter", "(Ljava/lang/Object;)V"));
    }

    private static void decorateMonitorExit(InsnList insts, AbstractInsnNode monIns, int frameVar) {
        insts.insertBefore(monIns, new InsnNode(Opcodes.DUP));
        AbstractInsnNode after = monIns.getNext();
        insts.insertBefore(after, new VarInsnNode(Opcodes.ALOAD, frameVar));
        insts.insertBefore(after, new InsnNode(Opcodes.SWAP));
        insts.insertBefore(after, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "monitorExit", "(Ljava/lang/Object;)V"));
    }

    private static void decorateExit(InsnList insts, AbstractInsnNode exitInst, int returnSize, Type[] stack, LabelNode returnPoint) {

        // We must remove everything from the stack, except for the top-most value, which is the value being returned.
        // Otherwise JVM will complain about mismatching frame contents. Redundant, but necessary.

        if (returnSize == 0) {
            // Returning void.
            clearStack(insts, exitInst, stack);
        } else {
            int last = stack.length - 1;
            if (returnSize == 1) {
                // Returning object, int or float.
                for (int i = last - 1; i >= 0; --i) {
                    switch (stack[i].getSize()) {
                        case 1:
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.SWAP));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP));
                            break;
                        case 2:
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.DUP_X2));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP2));
                            break;
                        default:
                            throw new AssertionError();
                    }
                }
            } else {
                // Returning long or double.
                for (int i = last - 1; i >= 0; --i) {
                    switch (stack[i].getSize()) {
                        case 1:
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.DUP2_X1));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP2));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP));
                            break;
                        case 2:
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.DUP2_X2));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP2));
                            insts.insertBefore(exitInst, new InsnNode(Opcodes.POP2));
                            break;
                        default:
                            throw new AssertionError();
                    }
                }
            }
        }

        insts.insertBefore(exitInst, new JumpInsnNode(Opcodes.GOTO, returnPoint));
        insts.remove(exitInst);
    }

    private static ResumeInfo decorateInvocation(MethodNode method, InsnList insts, AbstractInsnNode invIns, int frameVar, Type[] vars, Type[] stackBefore, Type[] stackAfter, int id,
            LabelNode returnPoint) {
        // Load frameVar. Will save the context invoking methods from frameVar.
        insts.insertBefore(invIns, new VarInsnNode(Opcodes.ALOAD, frameVar));

        // Invoke frame.notifyInvoke(id,maxPrim,maxObjs). This prepares the frame to save the context.
        assert id > 0;
        pushShort(insts, invIns, id);
        // This method receives 3 ints, but so far we've pushed 1 only. That's ok (see below).
        MethodInsnNode notifyIns = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "notifyInvoke", "(III)L" + FRAME_CLASS + ";");
        insts.insertBefore(invIns, notifyIns);

        // Save all local variables.
        int varCount = 0;
        int objVarCount = 0;

        for (int i = 0; i < vars.length; ++i) {
            if (vars[i] != null) {
                saveVar(i, vars[i], insts, invIns);
                if (isPrimitive(vars[i])) {
                    varCount += vars[i].getSize();
                } else {
                    ++objVarCount;
                }
            }
        }

        // Save all stack values (just temporary values, not local variables).
        int stackVarCount = 0;
        int stackObjVarCount = 0;
        for (int i = stackBefore.length - 1; i >= 0; --i) {
            saveStack(stackBefore[i], insts, invIns);
            if (isPrimitive(stackBefore[i])) {
                stackVarCount += stackBefore[i].getSize();
            } else {
                ++stackObjVarCount;
            }
        }

        // Now we can push the other 2 ints (see above).
        pushShort(insts, notifyIns, varCount + stackVarCount);
        pushShort(insts, notifyIns, objVarCount + stackObjVarCount);

        // At this point, there is a frame object on the stack. It will be used to restore stack values.
        // But if the number of stack values is zero, then the method will be called immediately.
        // On this situation, we must remove the frame object now.
        if (stackBefore.length == 0) {
            insts.insertBefore(invIns, new InsnNode(Opcodes.POP));
        }

        // Put a label used to resume execution. The header of this method will jump to this position when resuming.
        LabelNode befInvLabel = new LabelNode();
        insts.insertBefore(invIns, befInvLabel);

        // Restore stack values, so the method can be invoked normally.
        // In the last restore, the frame object is removed from the stack.
        for (int i = 0; i < stackBefore.length; ++i) {
            restoreStack(stackBefore[i], insts, invIns, i == stackBefore.length - 1);
        }

        // Here we invoke the method (no change, since this is the invIns instruction).

        // Now we must process the case where the thread must leave after invocation.

        // Add a label just after invocation. If the invoked function decides to continue, we'll jump here.
        LabelNode afterInvok = new LabelNode();
        insts.insert(invIns, afterInvok);

        // Check if it's continuing or leaving.
        insts.insertBefore(afterInvok, new VarInsnNode(Opcodes.ALOAD, frameVar));
        insts.insertBefore(afterInvok, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "isLeaving", "()Z"));

        // If resuming, jump to the instruction that follows invocation.
        insts.insertBefore(afterInvok, new JumpInsnNode(Opcodes.IFEQ, afterInvok));

        // Otherwise (leaving), jump to the return point.

        // We must remove whatever is on stack after invocation, because the return point
        // must contain only the returned value (or nothing if void). This is redundant,
        // but JVM will complain if we don't do this.
        clearStack(insts, afterInvok, stackAfter);

        Type returnType = Type.getReturnType(method.desc);
        int returnSort = returnType.getSort();
        if (returnSort != Type.VOID) {
            getResult(returnType, insts, afterInvok, frameVar);
        }
        insts.insertBefore(afterInvok, new JumpInsnNode(Opcodes.GOTO, returnPoint));

        // Return ResumeInfo, so we can add code to the header, that jumps to this instruction upon resuming.
        ResumeInfo ri = new ResumeInfo(id, befInvLabel, (MethodInsnNode) invIns, vars, varCount, objVarCount, stackVarCount, stackObjVarCount);

        return ri;
    }

    private static void clearStack(InsnList insts, AbstractInsnNode position, Type[] stack) throws AssertionError {
        for (int i = stack.length - 1; i >= 0; --i) {
            switch (stack[i].getSize()) {
                case 1:
                    insts.insertBefore(position, new InsnNode(Opcodes.POP));
                    break;
                case 2:
                    insts.insertBefore(position, new InsnNode(Opcodes.POP2));
                    break;
                default:
                    throw new AssertionError();
            }
        }
    }

    private static void pushShort(InsnList insts, AbstractInsnNode location, int value) {
        if (value >= -1 && value <= 5) {
            insts.insertBefore(location, new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= -128 && value <= 127) {
            insts.insertBefore(location, new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= -32768 && value <= 32767) {
            insts.insertBefore(location, new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            throw new IllegalArgumentException("Value does not fit on short: " + value);
        }
    }

    private static int getReturnOpcode(Type returnType) {
        int returnSort = returnType.getSort();
        switch (returnSort) {
            case Type.VOID:
                return Opcodes.RETURN;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return Opcodes.IRETURN;
            case Type.LONG:
                return Opcodes.LRETURN;
            case Type.FLOAT:
                return Opcodes.FRETURN;
            case Type.DOUBLE:
                return Opcodes.DRETURN;
            case Type.OBJECT:
            case Type.ARRAY:
                return Opcodes.ARETURN;
            default:
                throw new IllegalStateException(returnType.toString());
        }
    }

    private boolean isFlowMethod(MethodInsnNode ins) {
        MethodKey mk = new MethodKey(ins.owner, ins.name, ins.desc);
        // System.out.printf("Method %s is flow: %s.\n", mk, isFlowMethod(mk));
        return isFlowMethod(mk);
    }

    private boolean isFlowMethod(MethodKey mk) {
        Boolean ret = methodCache.get(mk);
        if (ret != null) {
            return ret;
        }
        try {
            if (mk.getOwner().startsWith("org/junit/") || mk.getOwner().startsWith("java/") || mk.getOwner().startsWith("sun/") || mk.getOwner().startsWith("com/sun/")) {
                methodCache.put(mk, false);
                return false;
            }
            readClass(mk.getOwner());
            MethodKey orig = mk;
            for (;;) {
                ret = methodCache.get(mk);
                if (ret != null) {
                    if (mk != orig) {
                        methodCache.put(orig, ret);
                    }
                    return ret;
                }
                String base = knownClasses.get(mk.getOwner());
                if (base == null) {
                    if (mk != orig) {
                        methodCache.put(orig, false);
                    }
                    return false;
                }
                mk = new MethodKey(base, mk.getName(), mk.getDesc());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void readClass(final String className) throws IOException {
        if (knownClasses.containsKey(className)) {
            return;
        }
        knownClasses.put(className, null);
        IClassResource clazz = getClassResource(className);
        if (clazz == null) {
            return;
        }
        String superName = clazz.getSuperName();
        if (superName != null) {
            knownClasses.put(className, superName);
            readClass(superName);
        }
        String[] interfaces = clazz.getInterfaces();
        for (String intfName : interfaces) {
            readClass(intfName);
        }
        IMethod[] methods = clazz.getMethods();
        final HashSet<MethodKey> falseMethods = new HashSet<MethodKey>();
        final HashSet<MethodKey> trueMethods = new HashSet<MethodKey>();
        for (IMethod method : methods) {
            final MethodKey curMethod = new MethodKey(className, method.getName(), method.getDescriptor());
            if (method.containsAnnotation(FLOWMETHOD_ANNOT)) {
                trueMethods.add(curMethod);
            } else {
                falseMethods.add(curMethod);
            }
        }
        for (MethodKey key : trueMethods) {
            methodCache.put(key, true);
        }
        for (MethodKey key : falseMethods) {
            methodCache.put(key, false);
        }
    }

    private IClassResource getClassResource(String className) throws IOException {
        IClassResource clazz = classCache.get(className);
        if (clazz != null) {
            return clazz;
        }
        String resName = className + ".class";
        clazz = classProvider.getClass(resName);
        if (clazz == null) {
            LightWolfLog.println("[WARNING] +----------------------------------------");
            LightWolfLog.println("[WARNING] | Resource not found: " + resName);
            LightWolfLog.println("[WARNING] +----------------------------------------");
            return null;
        }
        classCache.put(className, clazz);
        return clazz;
    }

    private static Type[] getVarTypes(Frame frame, boolean isStatic) {
        int numLocals = frame.getLocals();
        Type[] ret = new Type[numLocals];
        for (int i = isStatic ? 0 : 1; i < numLocals; ++i) {
            BasicValue value = (BasicValue) frame.getLocal(i);
            if (value != null) {
                ret[i] = value.getType();
            }
        }
        return ret;
    }

    private static Type[] getStackTypes(Frame frame) {
        Type[] ret = new Type[frame.getStackSize()];
        for (int i = 0; i < ret.length; ++i) {
            BasicValue value = (BasicValue) frame.getStack(i);
            ret[i] = value.getType();
        }
        return ret;
    }

    /**
     * Saves the value of a variable. Suppose the stack here will be
     * [...,frame], and suppose we must save the variable i, which is a float.
     * Here is how:
     * 
     * <pre>
     *    [...,frame]
     *       fload varN
     *    [...,frame,varN]
     *       invokevirtual // saveVar(varN), returns frame.
     *    [...,frame]
     * </pre>
     * 
     * @param varIndex Local variable index.
     * @param type Local variable type.
     * @param insts Instruction list where code will be generated.
     * @param location Instruction telling where code will be generated.
     *        Generated code will be before this.
     */
    private static void saveVar(int varIndex, Type type, InsnList insts, AbstractInsnNode location) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                insts.insertBefore(location, new VarInsnNode(Opcodes.ILOAD, varIndex));
                break;
            case Type.LONG:
                insts.insertBefore(location, new VarInsnNode(Opcodes.LLOAD, varIndex));
                break;
            case Type.FLOAT:
                insts.insertBefore(location, new VarInsnNode(Opcodes.FLOAD, varIndex));
                break;
            case Type.DOUBLE:
                insts.insertBefore(location, new VarInsnNode(Opcodes.DLOAD, varIndex));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                insts.insertBefore(location, new VarInsnNode(Opcodes.ALOAD, varIndex));
                type = OBJECT_TYPE;
                break;
            default:
                throw new RuntimeException(type.toString());
        }
        insts.insertBefore(location, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "save", "(" + type.getDescriptor() + ")L" + FRAME_CLASS + ";"));
    }

    private static void restoreVar(int varIndex, Type type, InsnList insts, AbstractInsnNode location) {
        String prefix;
        String desc;
        if (isPrimitive(type)) {
            prefix = getNiceName(type);
            desc = type.getDescriptor();
        } else {
            prefix = "Object";
            desc = OBJECT_TYPE.getDescriptor();
        }
        insts.insertBefore(location, new InsnNode(Opcodes.DUP));
        insts.insertBefore(location, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "restore" + prefix, "()" + desc));
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                insts.insertBefore(location, new VarInsnNode(Opcodes.ISTORE, varIndex));
                break;
            case Type.LONG:
                insts.insertBefore(location, new VarInsnNode(Opcodes.LSTORE, varIndex));
                break;
            case Type.FLOAT:
                insts.insertBefore(location, new VarInsnNode(Opcodes.FSTORE, varIndex));
                break;
            case Type.DOUBLE:
                insts.insertBefore(location, new VarInsnNode(Opcodes.DSTORE, varIndex));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                insts.insertBefore(location, new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                insts.insertBefore(location, new VarInsnNode(Opcodes.ASTORE, varIndex));
                type = OBJECT_TYPE;
                break;
            default:
                throw new RuntimeException(type.toString());
        }
    }

    private static String getNiceName(Type type) {
        String prefix;
        prefix = type.getClassName();
        prefix = Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1);
        return prefix;
    }

    /**
     * Saves the value of the last item on the stack (ignoring the frame
     * variable), and removes it. Suppose the stack here will be
     * [...,A,B,frame]. We must save B and let A and frame in the stack. Here is
     * how:
     * 
     * <pre>
     *    [...,A,B,frame]
     *       swap
     *    [...,A,frame,B]
     *       invokevirtual // save(B), returns frame.
     *    [...,A,frame]
     * </pre>
     * 
     * But if B is long or double, then:
     * 
     * <pre>
     *    [...,A,B,frame]
     *       dup_x2
     *    [...,A,frame,B,frame]
     *       pop
     *    [...,A,frame,B]
     *       invokevirtual // save(B), returns frame.
     *    [...,A,frame]
     * </pre>
     */
    private static void saveStack(Type type, InsnList insts, AbstractInsnNode cur) {
        if (type.getSize() == 1) {
            insts.insertBefore(cur, new InsnNode(Opcodes.SWAP));
        } else {
            insts.insertBefore(cur, new InsnNode(Opcodes.DUP_X2));
            insts.insertBefore(cur, new InsnNode(Opcodes.POP));
        }
        if (isObject(type)) {
            type = OBJECT_TYPE;
        }
        insts.insertBefore(cur, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "save", "(" + type.getDescriptor() + ")L" + FRAME_CLASS + ";"));
    }

    private static void restoreStack(final Type type, InsnList insts, AbstractInsnNode cur, boolean isLast) {
        /* The stack here will be [...,frame]. We've just saved all method parameters (say A,B), but
         * since we need to invoke the method, we need to restore the parameters!! Here is how:
         *    [...,frame]
         *       dup
         *    [...,frame,frame]
         *       invokevirtual // restore(), returns A.
         *    [...,frame,A]
         *       swap
         *    [...,A,frame] // Ready to the next restore.
         *
         * But wait! If we are in the last restore, then we don't need to let frame in the stack.
         * Here is how:
         *    [...,A,frame] // From the last restore.
         *       invokevirtual // restore(), returns B.
         *    [...,A,B] // Ready to invoke user method.
         */
        if (!isLast) {
            insts.insertBefore(cur, new InsnNode(Opcodes.DUP));
        }
        String prefix;
        String desc;
        if (isPrimitive(type)) {
            prefix = getNiceName(type);
            desc = type.getDescriptor();
        } else {
            prefix = "Object";
            desc = OBJECT_TYPE.getDescriptor();
        }
        insts.insertBefore(cur, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "restore" + prefix, "()" + desc));
        if (prefix == "Object") {
            insts.insertBefore(cur, new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
        }
        if (!isLast) {
            if (type.getSize() == 1) {
                insts.insertBefore(cur, new InsnNode(Opcodes.SWAP));
            } else {
                insts.insertBefore(cur, new InsnNode(Opcodes.DUP2_X1));
                insts.insertBefore(cur, new InsnNode(Opcodes.POP2));
            }
        }
    }

    private static void getResult(final Type type, InsnList insts, AbstractInsnNode cur, int frameVar) {
        insts.insertBefore(cur, new VarInsnNode(Opcodes.ALOAD, frameVar));
        String prefix;
        String desc;
        if (isPrimitive(type)) {
            prefix = getNiceName(type);
            desc = type.getDescriptor();
        } else {
            prefix = "Object";
            desc = OBJECT_TYPE.getDescriptor();
        }
        insts.insertBefore(cur, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME_CLASS, "getResult" + prefix, "()" + desc));
        if (prefix == "Object") {
            insts.insertBefore(cur, new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
        }
    }

    private static void printTrace(MethodNode method, Frame[] frames, InsnList insts) {
        LightWolfLog.printf("Method: %s %s.\n", method.name, method.signature);
        for (int i = 0; i < insts.size(); ++i) {
            LightWolfLog.printf("%3d: ", i + 1);
            if (frames != null) {
                LightWolfLog.println(frames[i]);
            }
            AbstractInsnNode inst = insts.get(i);
            if (inst.getOpcode() >= 0) {
                LightWolfLog.print("    ");
            }
            LightWolfLog.println(toString(inst));
        }
        for (int i = 0; i < method.tryCatchBlocks.size(); ++i) {
            TryCatchBlockNode tcb = (TryCatchBlockNode) method.tryCatchBlocks.get(i);
            LightWolfLog.printf("catch(%s) start: %s, end: %s, handler: %s.", tcb.type, tcb.start.getLabel(), tcb.end.getLabel(), tcb.handler.getLabel());
        }
        LightWolfLog.println();
        LightWolfLog.println();
    }

    public static String toString(AbstractInsnNode inst) {
        StringBuilder builder = new StringBuilder();
        append(inst, builder);
        return builder.toString();
    }

    public static void append(AbstractInsnNode inst, StringBuilder dest) {
        if (inst instanceof LabelNode) {
            LabelNode curInst = (LabelNode) inst;
            dest.append(curInst.getLabel());
            dest.append(":");
            return;
        }
        if (inst instanceof LineNumberNode) {
            LineNumberNode curInst = (LineNumberNode) inst;
            dest.append("; line: ");
            dest.append(curInst.line);
            dest.append(", ");
            dest.append(curInst.start.getLabel());
            return;
        }
        if (inst instanceof VarInsnNode) {
            VarInsnNode curInst = (VarInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.var);
            return;
        }
        if (inst instanceof MethodInsnNode) {
            MethodInsnNode curInst = (MethodInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.owner);
            dest.append(' ');
            dest.append(curInst.name);
            dest.append(' ');
            dest.append(curInst.desc);
            return;
        }
        if (inst instanceof InsnNode) {
            InsnNode curInst = (InsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            return;
        }
        if (inst instanceof LdcInsnNode) {
            LdcInsnNode curInst = (LdcInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.cst);
            return;
        }
        if (inst instanceof JumpInsnNode) {
            JumpInsnNode curInst = (JumpInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.label.getLabel());
            return;
        }
        if (inst instanceof TypeInsnNode) {
            TypeInsnNode curInst = (TypeInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.desc);
            return;
        }
        if (inst instanceof FieldInsnNode) {
            FieldInsnNode curInst = (FieldInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.owner);
            dest.append(' ');
            dest.append(curInst.name);
            dest.append(' ');
            dest.append(curInst.desc);
            return;
        }
        if (inst instanceof IntInsnNode) {
            IntInsnNode curInst = (IntInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.operand);
            return;
        }
        if (inst instanceof IincInsnNode) {
            IincInsnNode curInst = (IincInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.var);
            dest.append(' ');
            dest.append(curInst.incr);
            return;
        }
        if (inst instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode curInst = (TableSwitchInsnNode) inst;
            dest.append(AbstractVisitor.OPCODES[curInst.getOpcode()]);
            dest.append(' ');
            dest.append(curInst.min);
            dest.append("->");
            dest.append(curInst.max);
            for (int i = curInst.min; i <= curInst.max; ++i) {
                LabelNode label = (LabelNode) curInst.labels.get(i - curInst.min);
                if (label == null) {
                    continue;
                }
                dest.append(' ');
                dest.append(i);
                dest.append(": ");
                dest.append(label.getLabel());
            }
            if (curInst.dflt != null) {
                dest.append(" def: ");
                dest.append(curInst.dflt.getLabel());
            }
            return;
        }
        throw new RuntimeException("Unexpected instruction class: " + inst.getClass().getName());
    }

    private static boolean isPrimitive(Type type) {
        switch (type.getSort()) {
            case Type.CHAR:
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
            case Type.LONG:
            case Type.FLOAT:
            case Type.DOUBLE:
                return true;
        }
        return false;
    }

    private static boolean isObject(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT:
            case Type.ARRAY:
                return true;
        }
        return false;
    }

    private class Verifier extends SimpleVerifier {

        @Override
        protected boolean isInterface(Type t) {
            try {
                return super.isInterface(t);
            } catch (MustUseClassResource e) {
                if (t.getSort() != Type.OBJECT) {
                    return false;
                }
                IClassResource clazz = typeToClass(t);
                return clazz != null ? clazz.isInterface() : false;
            }
        }

        @Override
        protected Type getSuperClass(Type t) {
            try {
                return super.getSuperClass(t);
            } catch (MustUseClassResource e) {
                switch (t.getSort()) {
                    case Type.ARRAY:
                        return Type.getObjectType("java/lang/Object");
                    case Type.OBJECT:
                        if (t.getInternalName().equals("java/lang/Object")) {
                            return null;
                        }
                        IClassResource clazz = typeToClass(t);
                        if (clazz != null && clazz.getSuperName() != null) {
                            return Type.getObjectType(clazz.getSuperName());
                        }
                        return Type.getObjectType("java/lang/Object");
                    default:
                        return null;
                }
            }
        }

        @Override
        protected boolean isAssignableFrom(Type t, Type u) {
            try {
                return super.isAssignableFrom(t, u);
            } catch (MustUseClassResource e) {
                int tsort = t.getSort();
                int usort = u.getSort();
                if (tsort == Type.ARRAY && usort == Type.ARRAY) {
                    Type tet = t.getElementType();
                    Type uet = u.getElementType();
                    return isAssignableFrom(tet, uet);
                }
                if (tsort == Type.OBJECT && usort == Type.ARRAY) {
                    String tn = t.getInternalName();
                    return tn.equals("null") || //
                            tn.equals("java/lang/Object") || //
                            tn.equals("java/lang/Cloneable") || // 
                            tn.equals("java/io/Serializable");
                }

                if (tsort == Type.OBJECT && usort == Type.OBJECT) {
                    String tn = t.getInternalName();
                    if (tn.equals("null") || tn.equals("java/lang/Object")) {
                        return true;
                    }
                    String un = u.getInternalName();
                    if (un.equals("java/lang/Object")) {
                        return false;
                    }
                    IClassResource tc = typeToClass(t);
                    IClassResource uc = typeToClass(u);
                    if (!tc.isInterface()) {
                        if (uc.isInterface()) {
                            return false;
                        }
                        for (;;) {
                            String usuper = uc.getSuperName();
                            if (usuper == null) {
                                return false;
                            }
                            if (usuper.equals(tn)) {
                                return true;
                            }
                            uc = typeToClass(Type.getObjectType(usuper));
                            if (uc == null) {
                                return false;
                            }
                        }
                    }
                    if (uc.isInterface()) {
                        return isBaseOfSomeIntf(t, uc);
                    }
                    for (;;) {
                        if (isBaseOfSomeIntf(t, uc)) {
                            return true;
                        }
                        String usuper = uc.getSuperName();
                        if (usuper == null) {
                            return false;
                        }
                        uc = typeToClass(Type.getObjectType(usuper));
                        if (uc == null) {
                            return false;
                        }
                    }
                }
                return false;
            }
        }

        private boolean isBaseOfSomeIntf(Type t, IClassResource uc) {
            String[] uintfs = uc.getInterfaces();
            for (String uintf : uintfs) {
                if (isAssignableFrom(t, Type.getObjectType(uintf))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected Class<?> getClass(Type t) {
            throw new MustUseClassResource();
        }

        private IClassResource typeToClass(Type t) {
            try {
                return getClassResource(t.getInternalName());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private static class MustUseClassResource extends RuntimeException {

        private static final long serialVersionUID = 1L;

    }

    private static class ResumeInfo {

        final int invocationId;
        final LabelNode befInvLabel;
        final MethodInsnNode invocInst;
        final Type[] vars;
        final int varCount;
        final int objVarCount;
        final int stackVarCount;
        final int stackObjVarCount;

        public ResumeInfo(int invocationId, LabelNode befInvLabel, MethodInsnNode invocInst, Type[] vars, int varCount, int objVarCount, int stackVarCount, int stackObjVarCount) {
            this.invocationId = invocationId;
            this.befInvLabel = befInvLabel;
            this.invocInst = invocInst;
            this.vars = vars;
            this.varCount = varCount;
            this.objVarCount = objVarCount;
            this.stackVarCount = stackVarCount;
            this.stackObjVarCount = stackObjVarCount;
        }

    }

    private static class LightWolfEnhancedAttribute extends Attribute {

        private LightWolfEnhancedAttribute() {
            super(LIGHTWOLF_ENHANCED);
        }

        @Override
        protected ByteVector write(ClassWriter cw, byte[] code, int len, int maxStack, int maxLocals) {
            return new ByteVector();
        }

    }

    /*
     *  Tracking method invocations.
     *
     *  Before:
     *    [...,A,B]
     *       invokeSomething
     *    [...]
     *
     *  After:
     *    [...,A,B]
     *       aload frame
     *
     *    [...,A,B,frame]
     *       dup_x1
     *    [...,A,frame,B,frame]
     *       pop
     *    [...,A,frame,B]
     *       invokeVirtual // push(B), returns frame.
     *
     *    [...,A,frame]
     *       dup_x1
     *    [...,frame,A,frame]
     *       pop
     *    [...,frame,A]
     *       invokeVirtual // push(A), returns frame.
     *
     *    [...,frame]
     *       dup
     *    [...,frame,frame]
     *       invokeVirtual // restore(), returns A.
     *    [...,frame,A]
     *       dup_x1
     *    [...,A,frame,A]
     *       pop
     *
     *    [...,A,frame]
     *       invokeVirtual // restore(), returns B.
     *    [...,A,B]
     *       invokeSomething
     *    [...]
     */

    /* Antes:
     *      REGION_A();
     *      monitorEnter x;
     *      REGION_B();
     *      x.m(a,b,c);
     *      REGION_C();
     *      monitorExit x;
     *      REGION_D();
     *
     * Depois:
     *      frame = GETFRAME();
     *      if (frame.state == Inside_x_m) {
     *          RESTORECONTEXT(frame);
     *          $m1 = NEXTMONITOR(frame);
     *          monitorEnter $m1
     *          goto LInside_x_m;
     *      }
     *      assert frame.state == -1;
     *      REGION_A();
     *      monitorEnter x;
     *      MONITORENTER(frame, x);
     *      REGION_B();
     *      SAVECONTEXT(frame);
     *    LInside_x_m:
     *      x.m(a,b,c);
     *      REGION_C();
     *      monitorExit x;
     *      MONITOREXIT(frame, x);
     *      REGION_D();
     */

}

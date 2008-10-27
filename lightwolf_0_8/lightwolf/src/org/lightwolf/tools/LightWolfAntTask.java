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
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.Path;
import org.lightwolf.FlowMethod;

/**
 * An Ant task that enhances classes that uses {@link FlowMethod} annotation.
 * This task reads the specified .class files, looking for
 * {@linkplain FlowMethod flow methods} in each file. If a class contains flow
 * methods, each of them will have its bytecode enhanced, and the class is
 * rewritten to file system. Classes without flow methods are untouched.
 * <p>
 * The classes to enhance are specified in the <code>classesdir</code>
 * attribute, which contains directories separated by semicolon. If an entry in
 * the <code>classesdir</code> is a JAR or ZIP, such file is not itself
 * transformed or changed in any way, but their classes are read to check
 * whether they contain flow methods that are referenced by enhanced classes.
 * <p>
 * This task is a matching task, which means that you can add selectors such as
 * <code>&lt;exclude&gt;</code> and <code>&lt;different&gt;</code>. Below
 * are some examples:
 * <p>
 * Example 1: enhances all classes in the <code>bin</code> directory:
 * 
 * <pre>
 * &lt;project name=&quot;Sample&quot; default=&quot;main&quot;&gt;
 *     &lt;taskdef name=&quot;lightwolf&quot; classname=&quot;org.lightwolf.tools.LightWolfAntTask&quot; /&gt;
 *     &lt;target name=&quot;main&quot;&gt;
 *         &lt;lightwolf classesdir=&quot;bin&quot;/&gt;
 *     &lt;/target&gt;
 * &lt;/project&gt;
 * </pre>
 * 
 * Example 2: enhances all classes in the <code>bin</code> directory, except
 * <code>Test*</code> classes:
 * 
 * <pre>
 * &lt;project name=&quot;Sample&quot; default=&quot;main&quot;&gt;
 *     &lt;taskdef name=&quot;lightwolf&quot; classname=&quot;org.lightwolf.tools.LightWolfAntTask&quot; /&gt;
 *     &lt;target name=&quot;main&quot;&gt;
 *         &lt;lightwolf classesdir=&quot;bin&quot;&gt;
 *             &lt;exclude name=&quot;&#42;&#42;/Test*&quot;/&gt;
 *         &lt;/lightwolf&gt;
 *     &lt;/target&gt;
 * &lt;/project&gt;
 * </pre>
 * 
 * Example 3: enhances all classes in the <code>lwbin</code> directory, except
 * those that are identical to classes in the <code>bin</code> directory:
 * 
 * <pre>
 * &lt;project name=&quot;Sample&quot; default=&quot;main&quot;&gt;
 *     &lt;taskdef name=&quot;lightwolf&quot; classname=&quot;org.lightwolf.tools.LightWolfAntTask&quot; /&gt;
 *     &lt;target name=&quot;main&quot;&gt;
 *         &lt;lightwolf classesdir=&quot;lwbin&quot;&gt;
 *             &lt;different targetdir=&quot;bin&quot; ignoreFileTimes=&quot;true&quot;/&gt;
 *         &lt;/lightwolf&gt;
 *     &lt;/target&gt;
 * &lt;/project&gt;
 * </pre>
 * 
 * Example 4: enhances all classes in the <code>bin1</code> and
 * <code>bin2</code> directories, considering flow methods that might be in
 * the <code>referenced.jar</code> file.
 * 
 * <pre>
 * &lt;project name=&quot;Sample&quot; default=&quot;main&quot;&gt;
 *     &lt;taskdef name=&quot;lightwolf&quot; classname=&quot;org.lightwolf.tools.LightWolfAntTask&quot; /&gt;
 *     &lt;target name=&quot;main&quot;&gt;
 *         &lt;lightwolf classesdir=&quot;bin1;bin2;referenced.jar&quot;/&gt;
 *     &lt;/target&gt;
 * &lt;/project&gt;
 * </pre>
 * 
 * @author Fernando Colombo
 */
public class LightWolfAntTask extends MatchingTask {

    public static void main(String[] args) {
        // TODO: Method for testing purpouses; move to someplace else when done.
        LightWolfEnhancer.changeFile = false;
        Project p = new Project();
        LightWolfAntTask wat = new LightWolfAntTask();
        wat.setProject(p);
        wat.setClassesdir(new Path(p, "bin"));
        wat.execute();
    }

    private Path classesDir;

    public void setClassesdir(Path srcDir) {
        if (classesDir == null) {
            classesDir = srcDir;
        } else {
            classesDir.append(srcDir);
        }
    }

    public Path getClassesdir() {
        return classesDir;
    }

    @Override
    public void execute() throws BuildException {

        try {

            String[] list = classesDir.list();
            URL[] urls = stringsToURLs(list);
            LightWolfEnhancer enhancer = new LightWolfEnhancer(new URLClassLoader(urls));

            for (int i = 0; i < list.length; i++) {
                File dir = getProject().resolveFile(list[i]);
                if (dir.isFile()) {
                    continue;
                }
                if (!dir.exists()) {
                    throw new BuildException("Directory \"" + dir.getPath() + "\" does not exist!", getLocation());
                }
                DirectoryScanner ds = getDirectoryScanner(dir);
                String[] files = ds.getIncludedFiles();
                for (String s : files) {
                    File f = new File(dir, s);
                    int result = enhancer.transform(f);
                    if (result != LightWolfEnhancer.DONT_NEED_TRANSFORM) {
                        System.out.printf("%s: %s.\n", f.getAbsolutePath(), LightWolfEnhancer.getResultName(result));
                    }
                }

            }

            TimeCounter.dump();

        } catch (RuntimeException e) {
            e.printStackTrace();
            throw e;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private URL[] stringsToURLs(String[] list) throws MalformedURLException {
        URL[] ret = new URL[list.length];
        for (int i = 0; i < ret.length; ++i) {
            ret[i] = new URL("file://" + list[i].replace('\\', '/'));
        }
        return ret;
    }

}

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

public class LightWolfAntTask extends MatchingTask {

    public static void main(String[] args) {
        // TODO: Method for testing purpouses; move to someplace else when done.
        BookKeepEnhancer.changeFile = false;
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
            BookKeepEnhancer enhancer = new BookKeepEnhancer(new URLClassLoader(urls));

            for (int i = 0; i < list.length; i++) {
                File dir = getProject().resolveFile(list[i]);
                if (!dir.exists()) {
                    throw new BuildException("Directory \"" + dir.getPath() + "\" does not exist!", getLocation());
                }
                DirectoryScanner ds = getDirectoryScanner(dir);
                String[] files = ds.getIncludedFiles();
                for (String s : files) {
                    File f = new File(dir, s);
                    System.out.println("Looking file " + f.getAbsolutePath() + "...");
                    if (enhancer.transform(f)) {
                        System.out.println("File " + f.getAbsolutePath() + " was enhanced.");
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

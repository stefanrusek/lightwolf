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
package org.lightwolf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

import org.lightwolf.tools.DebuggingObjectOutputStream;

/**
 * A task that stores data on a file.
 * 
 * @author Fernando Colombo
 */
public class FileTask extends Task {

    private static final long serialVersionUID = 1L;
    private final File file;

    public FileTask(TaskManager manager, File file) {
        super(manager);
        if (file == null) {
            throw new NullPointerException();
        }
        this.file = file;
    }

    public FileTask(File file) {
        if (file == null) {
            throw new NullPointerException();
        }
        this.file = file;
    }

    public FileTask(TaskManager manager, String fileName) {
        super(manager);
        if (fileName == null) {
            throw new NullPointerException();
        }
        file = new File(fileName);
    }

    public FileTask(String fileName) {
        if (fileName == null) {
            throw new NullPointerException();
        }
        file = new File(fileName);
    }

    @Override
    protected void storeData(Object data) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        DebuggingObjectOutputStream oos = new DebuggingObjectOutputStream(fos);
        try {
            oos.writeObject(data);
        } catch (Exception e) {
            throw new RuntimeException("Serialization error. Path to bad object: " + oos.getStack(), e);
        } finally {
            oos.close();
        }
    }

    @Override
    protected Object loadData() throws IOException, ClassNotFoundException {
        FileInputStream fis = new FileInputStream(file);
        ObjectInputStream ois = new ObjectInputStream(fis);
        try {
            return ois.readObject();
        } finally {
            ois.close();
        }
    }

    @Override
    protected void discardData() throws IOException {
        file.delete();
    }

}

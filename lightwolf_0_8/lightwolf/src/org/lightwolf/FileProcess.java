package org.lightwolf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

import org.lightwolf.tools.DebuggingObjectOutputStream;

public class FileProcess extends AbstractPersistentProcess {

    private final File file;

    public FileProcess(ProcessManager manager, File file) {
        super(manager);
        if (file == null) {
            throw new NullPointerException();
        }
        this.file = file;
    }

    public FileProcess(File file) {
        if (file == null) {
            throw new NullPointerException();
        }
        this.file = file;
    }

    public FileProcess(ProcessManager manager, String fileName) {
        super(manager);
        if (fileName == null) {
            throw new NullPointerException();
        }
        file = new File(fileName);
    }

    public FileProcess(String fileName) {
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

}

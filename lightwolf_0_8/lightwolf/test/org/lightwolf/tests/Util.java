package org.lightwolf.tests;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.lightwolf.tools.PublicByteArrayInputStream;
import org.lightwolf.tools.PublicByteArrayOutputStream;

public class Util {

    public static Object streamedCopy(Object o) {
        try {
            PublicByteArrayOutputStream baos = new PublicByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(o);
            oos.close();
            PublicByteArrayInputStream bais = new PublicByteArrayInputStream(baos.getBuffer(), 0, baos.size());
            ObjectInputStream ois = new ObjectInputStream(bais);
            Object ret = ois.readObject();
            ois.close();
            return ret;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}

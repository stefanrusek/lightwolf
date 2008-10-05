package org.lightwolf.tools;

import java.io.IOException;

public interface IClassProvider {

    IClassResource getClass(String resName) throws IOException;

}

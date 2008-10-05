package org.lightwolf.tools;


public interface IMethod {

    String getName();

    String getDescriptor();

    boolean containsAnnotation(String annot);

}

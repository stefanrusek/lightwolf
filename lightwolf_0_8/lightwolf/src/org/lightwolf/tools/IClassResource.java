package org.lightwolf.tools;

public interface IClassResource {

    final String[] NO_INTERFACES = new String[0];
    final IMethod[] NO_METHODS = new IMethod[0];

    String getSuperName();

    String[] getInterfaces();

    IMethod[] getMethods();

}

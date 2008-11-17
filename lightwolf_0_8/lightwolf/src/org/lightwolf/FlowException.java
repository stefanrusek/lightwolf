package org.lightwolf;

/**
 * An exception that encapsulates another, thrown by a flow.
 * 
 * @author Fernando Colombo
 */
public class FlowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FlowException(Throwable cause) {
        super(cause);
    }

}

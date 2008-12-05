package org.lightwolf;

public class AddressAlreadyInUseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AddressAlreadyInUseException(Object address) {
        super(address == null ? "null" : address.toString());
    }

}

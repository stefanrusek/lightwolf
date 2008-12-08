package org.lightwolf;

public class Connection {

    public void send(Object message) {
        throw pending();
    }

    public Object receive() {
        throw pending();
    }

    private RuntimeException pending() {
        return new RuntimeException();
    }

}

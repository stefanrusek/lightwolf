package org.lw_sample.entity;

import java.util.Collections;
import java.util.LinkedList;

public class Order {

    private LinkedList<Item> items;
    private OrderStatus status;
    private boolean closed;

    public Order() {
        this.items = new LinkedList<Item>();
    }

    public Iterable<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void addItem(Item item) {
        items.add(item);
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus value) {
        status = value;
    }

    boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean value) {
        closed = value;
    }

}

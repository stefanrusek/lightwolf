package org.lw_sample.test;

import org.lightwolf.Flow;
import org.lw_sample.entity.Item;
import org.lw_sample.entity.Order;
import org.lw_sample.sales.OrderDelivery;

public class TestOrderProcessor {

    public static void main(String[] args) throws Throwable {

        ShippingMock sm = new ShippingMock();
        //sm.setAddressPrefix("Shipping.");
        sm.start();

        InventoryMock im = new InventoryMock();
        //im.setAddressPrefix("Inventory.");
        im.start();

        OrderDelivery op = new OrderDelivery();

        for (int i = 0; i < 1000; ++i) {
            Order order = new Order();
            addItem(order, "PLASTIC-WHEEL", 4);
            addItem(order, "METALLIC-SHAFT", 2);
            addItem(order, "PLASTIC-PLATFORM", 1);
            addItem(order, "PLASTIC-COVER", 1);
            addItem(order, "RED-INK", 50.0);
            Flow flow = op.submit("process", order);
            flow.join();
            Object res = flow.getResult();
            System.out.printf("Result: %s.\n", res);
            if (res instanceof Throwable) {
                ((Throwable) res).printStackTrace();
            }
        }

    }

    private static void addItem(Order order, String productId, double quantity) {
        Item item = new Item();
        item.setProductId(productId);
        item.setQuantity(quantity);
        order.addItem(item);
    }

}

package org.lw_sample.sales;

import java.util.ArrayList;

import org.lightwolf.FlowMethod;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Item;
import org.lw_sample.entity.Order;
import org.lw_sample.entity.OrderStatus;
import org.lw_sample.entity.Pakage;
import org.lw_sample.entity.Reservation;
import org.lw_sample.entity.Shipment;
import org.lw_sample.entity.Storage;
import org.lw_sample.inventory.Inventory;

public class OrderProcessor {

    @FlowMethod
    public static void process(Order order) throws BusinessException {

        // Place a reservation on inventory for each item. This may trigger Purchase Orders.
        ArrayList<Reservation> reservations = new ArrayList<Reservation>();
        for (Item item : order.getItems()) {
            Reservation reservation = Inventory.placeReservation(item.getProductId(), item.getQuantity(), item.getReservationId());
            reservations.add(reservation);
            // Update each item with the reservation code.
            item.setReservationId(reservation.getId());
            Storage.save(item);
        }

        Shipment shipment;
        try {

            order.setStatus(OrderStatus.WAITING_FOR_STOCK);
            Storage.save(order);

            // Wait until all reservations are satisfied. Can be immediate or take days until all items are available.
            Inventory.waitForAvailability(reservations);

            // Time to assemble the package.
            // This will put a packaging activity on some employee's task list.
            // This method returns only when the package is ready for shipment.        
            Pakage pakage = Shipping.buildPackage(order);
            try {
                // The package is ready. Now we must schedule shipment.
                shipment = Shipping.scheduleShipment(pakage);

                order.setStatus(OrderStatus.WAITING_FOR_SHIPMENT);
                Storage.save(order);

                // Wait for the shipment.
                Shipping.waitForShipment(shipment);
            } catch (BusinessException e) {
                // We were unable to ship. So let's undo the package.
                Shipping.undoPackage(pakage);
                throw e;
            }

        } catch (BusinessException e) {
            // The order wasn't shipped. So let's cancel the reservations. 
            Inventory.cancelReservations(reservations);
            throw e;
        }

        // The order was shipped.
        order.setStatus(OrderStatus.SHIPPED);
        Storage.save(order);

        // Wait for the delivery.
        Shipping.waitForDelivery(shipment);

        // We are done.
        order.setStatus(OrderStatus.DELIVERED);
        order.setClosed(true);
        Storage.save(order);

    }
}

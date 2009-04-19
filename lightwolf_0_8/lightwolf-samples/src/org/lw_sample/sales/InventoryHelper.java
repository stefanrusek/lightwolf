package org.lw_sample.sales;

import java.util.ArrayList;

import org.lightwolf.FlowMethod;
import org.lightwolf.process.ServiceInvoker;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Product;
import org.lw_sample.entity.Reservation;
import org.lw_sample.entity.Storage;
import org.lw_sample.test.IInventory;

public class InventoryHelper {

    public static Reservation placeReservation(String productId, double quantity, String currentReservationId) throws BusinessException {
        Reservation ret;
        if (currentReservationId != null) {
            ret = Storage.getReservation(currentReservationId);
            if (ret == null) {
                throw new BusinessException("Could not find a reservation with id=" + currentReservationId + ".");
            }
            if (ret.isProcessed()) {
                Product product = getProduct(ret);
                product.setQuantity(product.getQuantity() - ret.getQuantity());
                ret.setProcessed(false);
            }
        } else {
            ret = Storage.newReservation();
        }
        ret.setProductId(productId);
        ret.setQuantity(quantity);
        Storage.save(ret);
        return ret;
    }

    @FlowMethod
    public static void waitForAvailability(ArrayList<Reservation> reservations) throws BusinessException {
        IInventory inventory = ServiceInvoker.getInstance(IInventory.class);
        inventory.waitForAvailability(reservations);
    }

    public static void cancelReservations(ArrayList<Reservation> reservations) throws BusinessException {
        ArrayList<Reservation> done = new ArrayList<Reservation>(reservations.size());
        try {
            for (Reservation reservation : reservations) {
                Product product = getProduct(reservation);
                product.setQuantity(product.getQuantity() + reservation.getQuantity());
                Storage.save(product);
                done.add(reservation);
            }
        } finally {
            if (done.size() < reservations.size()) {
                for (Reservation reservation : done) {
                    Product product = getProduct(reservation);
                    product.setQuantity(product.getQuantity() - reservation.getQuantity());
                    Storage.save(product);
                }
            }
        }
    }

    private static Product getProduct(Reservation ret) throws BusinessException {
        Product product = Storage.getProduct(ret.getProductId());
        if (product == null) {
            throw new BusinessException("Error in reservation with id=" + ret.getId() + ": could not find product with id=" + ret.getProductId() + ".");
        }
        return product;
    }

}

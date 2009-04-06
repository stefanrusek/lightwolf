package org.lw_sample.entity;

import java.util.HashMap;

import org.lw_sample.BusinessException;

public class Storage {

    private static HashMap<String, Product> products;
    private static int nextProductId;
    private static HashMap<String, Reservation> reservations;
    private static int nextReservationId;

    static {
        products = new HashMap<String, Product>();
        nextProductId = 1;
        reservations = new HashMap<String, Reservation>();
        nextReservationId = 1;
    }

    public static void save(Item item) throws BusinessException {
    // TODO Auto-generated method stub

    }

    public static void save(Order order) throws BusinessException {
    // TODO Auto-generated method stub

    }

    public static Reservation getReservation(String reservationId) {
        // TODO Auto-generated method stub
        return null;
    }

    public static synchronized Reservation newReservation() {
        Reservation ret = new Reservation();
        ret.setId(String.valueOf(nextReservationId++));
        reservations.put(ret.getId(), ret);
        return ret;
    }

    public static void save(Reservation ret) {
    // TODO Auto-generated method stub

    }

    public static Product getProduct(String productId) {
        return products.get(productId);
    }

    public static void save(Product product) throws BusinessException {
    // TODO Auto-generated method stub

    }

}

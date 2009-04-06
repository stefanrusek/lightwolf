package org.lw_sample.entity;

public class Reservation {

    private String id;
    private String productId;
    private double quantity;
    private boolean processed;

    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double value) {
        quantity = value;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean value) {
        processed = value;
    }

}

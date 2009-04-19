package org.lw_sample.test;

import org.lightwolf.process.Service;
import org.lightwolf.process.ServiceProvider;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Order;
import org.lw_sample.entity.Pakage;
import org.lw_sample.entity.Shipment;

public class ShippingMock extends ServiceProvider implements IShipping {

    @Service
    public Pakage buildPackage(Order order) throws BusinessException {
        System.out.println("Package is built.");
        return new Pakage();
    }

    @Service
    public Shipment scheduleShipment(Pakage pakage) throws BusinessException {
        System.out.println("Shipment is scheduled.");
        return new Shipment();
    }

    @Service
    public void waitForShipment(Shipment shipment) throws BusinessException {
        System.out.println("Product shipped.");
    }

    @Service
    public void undoPackage(Pakage pakage) throws BusinessException {
        System.out.println("Package is undone.");
    }

    @Service
    public void waitForDelivery(Shipment shipment) throws BusinessException {
        System.out.println("Shipment is delivered.");
    }

}

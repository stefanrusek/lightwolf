package org.lw_sample.test;

import org.lightwolf.FlowMethod;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Order;
import org.lw_sample.entity.Pakage;
import org.lw_sample.entity.Shipment;

public interface IShipping {

    @FlowMethod
    Pakage buildPackage(Order order) throws BusinessException;

    @FlowMethod
    Shipment scheduleShipment(Pakage pakage) throws BusinessException;

    @FlowMethod
    void waitForShipment(Shipment shipment) throws BusinessException;

    @FlowMethod
    void undoPackage(Pakage pakage) throws BusinessException;

    @FlowMethod
    void waitForDelivery(Shipment shipment) throws BusinessException;

}

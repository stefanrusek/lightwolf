package org.lw_sample.sales;

import org.lightwolf.FlowMethod;
import org.lightwolf.ResumeException;
import org.lightwolf.Task;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Order;
import org.lw_sample.entity.Pakage;
import org.lw_sample.entity.Shipment;

public class Shipping {

    @FlowMethod
    public static Pakage buildPackage(Order order) throws BusinessException {
        try {
            return Task.call("Shipping.BuildPackage", order, Pakage.class);
        } catch (ResumeException e) {
            throw BusinessException.buildException(e.getCause());
        }
    }

    @FlowMethod
    public static Shipment scheduleShipment(Pakage pakage) throws BusinessException {
        try {
            return Task.call("Shipping.ScheduleShipment", pakage, Shipment.class);
        } catch (ResumeException e) {
            throw BusinessException.buildException(e.getCause());
        }
    }

    @FlowMethod
    public static void waitForShipment(Shipment shipment) throws BusinessException {
        try {
            Task.callVoid("Shipping.WaitForShipment", shipment);
        } catch (ResumeException e) {
            throw BusinessException.buildException(e.getCause());
        }
    }

    @FlowMethod
    public static void undoPackage(Pakage pakage) throws BusinessException {
        try {
            Task.callVoid("Shipping.UndoPackage", pakage);
        } catch (ResumeException e) {
            throw BusinessException.buildException(e.getCause());
        }
    }

    @FlowMethod
    public static void waitForDelivery(Shipment shipment) throws BusinessException {
        try {
            Task.callVoid("Shipping.WaitForDelivery", shipment);
        } catch (ResumeException e) {
            throw BusinessException.buildException(e.getCause());
        }
    }

}

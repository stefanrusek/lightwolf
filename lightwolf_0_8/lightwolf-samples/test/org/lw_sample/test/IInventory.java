package org.lw_sample.test;

import java.util.ArrayList;

import org.lightwolf.FlowMethod;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Reservation;

public interface IInventory {

    @FlowMethod
    void waitForAvailability(ArrayList<Reservation> reservations) throws BusinessException;

}

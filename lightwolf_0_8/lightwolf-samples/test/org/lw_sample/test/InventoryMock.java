package org.lw_sample.test;

import java.util.ArrayList;

import org.lightwolf.process.Service;
import org.lightwolf.process.ServiceProvider;
import org.lw_sample.BusinessException;
import org.lw_sample.entity.Reservation;

public class InventoryMock extends ServiceProvider implements IInventory {

    @Service
    public void waitForAvailability(ArrayList<Reservation> reservations) throws BusinessException {
        System.out.println("Reservations are available.");
    }

}

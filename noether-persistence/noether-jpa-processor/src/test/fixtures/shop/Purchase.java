package shop;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Property access, an uncapitalised accessor, a boolean property and embedded values. */
@Entity
public class Purchase {
    private int number;
    private String description;
    private boolean paid;
    private Address delivery;
    private Geo pickup;

    @Id
    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getdescription() {
        return description;
    }

    public void setdescription(String description) {
        this.description = description;
    }

    public boolean isPaid() {
        return paid;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }

    @Embedded
    public Address getDelivery() {
        return delivery;
    }

    public void setDelivery(Address delivery) {
        this.delivery = delivery;
    }

    public Geo getPickup() {
        return pickup;
    }

    public void setPickup(Geo pickup) {
        this.pickup = pickup;
    }
}

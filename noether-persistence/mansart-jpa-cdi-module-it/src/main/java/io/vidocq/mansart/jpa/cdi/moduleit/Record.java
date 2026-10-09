package io.vidocq.mansart.jpa.cdi.moduleit;

import jakarta.persistence.*;

@Entity @Table(name = "container_record")
public class Record {
    @Id public int id;
    public String description;
    public Record() {}
    public Record(int id) { this.id = id; this.description = "initial"; }
}

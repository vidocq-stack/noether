package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.persistence.*;

/** An entity whose identifiers a generator table allocates, in blocks of 10. */
@Entity @Table(name = "container_voucher")
public class Voucher {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "vouchers")
    @TableGenerator(name = "vouchers", table = "CONTAINER_VOUCHER_IDS", initialValue = 1, allocationSize = 10)
    public Long id;
    public String code;
    public Voucher() {}
    public Voucher(String code) { this.code = code; }
}

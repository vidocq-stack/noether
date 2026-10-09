/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model.build.fixtures.rel;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;

/**
 * The owning side of to-one relationships: a default join column (dept_id), a named one referencing the same entity
 * (MGR), a lazy one (loaded at once all the same), and a one-to-one that removes its orphans.
 */
@Entity
@NamedQuery(name = "Emp.count", query = "SELECT COUNT(e) FROM Emp e")
@NamedQuery(name = "Emp.forUpdate", query = "SELECT e FROM Emp e WHERE e.id = :id", lockMode = LockModeType.PESSIMISTIC_WRITE)
@NamedNativeQuery(name = "Emp.rename", query = "UPDATE Emp SET name = ? WHERE id = ?")
public class Emp {
    @Id
    private long id;
    private String name;
    @Version
    private int version;
    @ManyToOne
    private Dept dept;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "MGR")
    private Emp manager;
    @OneToOne(cascade = CascadeType.PERSIST, orphanRemoval = true)
    @JoinColumn(name = "DESK_ID")
    private Desk desk;

    protected Emp() {
    }

    public Emp(long id, String name, Dept dept) {
        this.id = id;
        this.name = name;
        this.dept = dept;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public Dept dept() {
        return dept;
    }

    public void dept(Dept dept) {
        this.dept = dept;
    }

    public Emp manager() {
        return manager;
    }

    public void manager(Emp manager) {
        this.manager = manager;
    }

    public Desk desk() {
        return desk;
    }

    public void desk(Desk desk) {
        this.desk = desk;
    }
}

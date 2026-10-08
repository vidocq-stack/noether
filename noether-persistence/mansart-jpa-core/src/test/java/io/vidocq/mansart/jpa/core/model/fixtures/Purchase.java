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
package io.vidocq.mansart.jpa.core.model.fixtures;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.util.List;

/** A field-access entity with annotations of every value kind, for the class-file reader. */
@Entity(name = "Purchase")
@Table(name = "PURCHASES", uniqueConstraints = @UniqueConstraint(columnNames = {"CODE", "LABEL"}))
@IdClass(PurchaseKey.class)
public class Purchase {

    @Id
    private long id;

    @Id
    private String code;

    @Column(name = "LABEL", nullable = false, length = 40)
    private String label;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column
    private String plain;

    @AttributeOverride(name = "street", column = @Column(name = "SHIP_STREET"))
    private Address shipping;

    private List<String> tags;

    @Transient
    private String ignored;

    private static String constant;

    public enum Status { OPEN, CLOSED }

    protected Purchase() {
    }

    public String getLabel() {
        return label;
    }
}

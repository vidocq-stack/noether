/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.processorit.closed;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.ManyToOne;

@Embeddable
public record Profile(long revision, @ManyToOne(cascade = CascadeType.ALL) Account owner) {
}

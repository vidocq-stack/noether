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
package io.vidocq.mansart.jpa.core.model.build.fixtures;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Auto-applied to every char[] attribute: its generic signature holds a primitive array. */
@Converter(autoApply = true)
public class CharsConverter implements AttributeConverter<char[], String> {
    @Override
    public String convertToDatabaseColumn(char[] value) {
        return value == null ? null : new StringBuilder(new String(value)).reverse().toString();
    }

    @Override
    public char[] convertToEntityAttribute(String value) {
        return value == null ? null : new StringBuilder(value).reverse().toString().toCharArray();
    }
}

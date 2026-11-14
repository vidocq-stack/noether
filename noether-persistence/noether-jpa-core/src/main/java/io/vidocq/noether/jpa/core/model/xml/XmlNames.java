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
package io.vidocq.noether.jpa.core.model.xml;

/** The names of the elements and attributes of a mapping file (Jakarta Persistence 3.2, chapter 12) read more than once. */
final class XmlNames {

    static final String DESCRIPTION = "description";
    static final String PERSISTENCE_UNIT_METADATA = "persistence-unit-metadata";
    static final String PACKAGE = "package";
    static final String SCHEMA = "schema";
    static final String CATALOG = "catalog";
    static final String ACCESS = "access";
    static final String CLASS = "class";
    static final String METHOD_NAME = "method-name";
    static final String ENTITY = "entity";
    static final String MAPPED_SUPERCLASS = "mapped-superclass";
    static final String EMBEDDABLE = "embeddable";
    static final String CONVERTER = "converter";
    static final String ATTRIBUTES = "attributes";
    static final String ENTITY_LISTENERS = "entity-listeners";
    static final String ENTITY_LISTENER = "entity-listener";
    static final String SEQUENCE_GENERATOR = "sequence-generator";
    static final String TABLE_GENERATOR = "table-generator";
    static final String NAMED_QUERY = "named-query";
    static final String PRIMARY_KEY_JOIN_COLUMN = "primary-key-join-column";
    static final String ATTRIBUTE_OVERRIDE = "attribute-override";
    static final String ASSOCIATION_OVERRIDE = "association-override";
    static final String CONVERT = "convert";
    static final String CASCADE = "cascade";
    static final String MAPS_ID = "maps-id";

    private XmlNames() {
    }
}

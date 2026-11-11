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
package io.vidocq.noether.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261111-04: TABLE identifiers in a JTA unit on Open Liberty. Liberty enlists every connection of the JTA data
 * source and refuses a commit on it (DSRA9250E), so the unit allocates its blocks through its
 * {@code non-jta-data-source}, a {@code dataSource} with {@code transactional="false"} in {@code server.xml}.
 */
class TableGeneratorIT {

    private static final String BASE = System.getProperty("noether.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void aJtaUnitAllocatesTableIdentifiersThroughItsNonJtaDataSource() throws Exception {
        long id = Long.parseLong(post("/vouchers?code=first"));

        assertTrue(committed().contains(id), "the voucher is committed with its TABLE identifier " + id);
    }

    @Test
    void identifiersAllocatedForARolledBackTransactionAreNeverHandedOutAgain() throws Exception {
        long doomed = Long.parseLong(post("/vouchers/failing?code=doomed"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 11; i++) { // past the end of the block of 10 the rolled-back transaction drew from
            ids.add(Long.parseLong(post("/vouchers?code=next-" + i)));
        }

        assertEquals(ids.size(), new HashSet<>(ids).size(), "no identifier handed out twice: " + ids);
        assertFalse(ids.contains(doomed), "the rolled-back identifier " + doomed + " is not handed out again: " + ids);
        Set<Long> committed = committed();
        assertTrue(committed.containsAll(ids), "every voucher is committed: " + committed);
        assertFalse(committed.contains(doomed), "the server rolled the doomed voucher back: " + committed);
    }

    private static Set<Long> committed() throws IOException, InterruptedException {
        String body = send(HttpRequest.newBuilder(URI.create(BASE + "/vouchers")).GET());
        Set<Long> ids = new HashSet<>();
        for (String id : body.split(",")) {
            if (!id.isBlank()) ids.add(Long.parseLong(id.strip()));
        }
        return ids;
    }

    private static String post(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(BASE + path)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private static String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}

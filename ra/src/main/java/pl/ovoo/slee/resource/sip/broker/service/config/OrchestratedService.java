/*
 * SIP Service Broker Resource Adaptor
 * Copyright (C) 2016-2017 "OVOO Sp. z o.o."
 *
 * This file is part of the SIP Service Broker RA.
 *
 * SIP Service Broker RA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SIP Service Broker RA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pl.ovoo.slee.resource.sip.broker.service.config;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents an orchestrated application/service.
 * It keeps the persistent services data and manages last used endpoint (for round-robin load-balancing).
 */
public class OrchestratedService {

    private final String alias;
    private final boolean external;

    private final List<Endpoint> endpoints;

    // last used endpoint
    private int lastEndpointIndex;


    public OrchestratedService(String alias, boolean external) {
        this.alias = alias;
        this.external = external;
        endpoints = new ArrayList<>();
        lastEndpointIndex = 0;
    }

    /**
     * Adds new endpoint. This function is synchronized in order to avoid
     * messing up the nextEndpoint method iteration.
     *
     * @param endpoint
     */
    public synchronized void addEndpoint(Endpoint endpoint) {
        endpoints.add(endpoint);
        lastEndpointIndex = 0; // lets reset pointer, just in case
    }

    /**
     * Returns next endpoint to connect providing round-robin load balancing function.
     *
     * @return
     */
    public synchronized Endpoint nextEndpoint() {
        Endpoint nextEndpoint = endpoints.get(lastEndpointIndex);
        if (++lastEndpointIndex == endpoints.size()) {
            // end of list, reset pointer to the beginning
            lastEndpointIndex = 0;
        }
        return nextEndpoint;
    }

    /**
     * Returns list of all endpoints for this service.
     *
     * @return
     */
    public synchronized List<Endpoint> getEndpoints() {
        return endpoints;
    }

    public boolean isExternal() {
        return external;
    }

    public String getAlias() {
        return alias;
    }
}

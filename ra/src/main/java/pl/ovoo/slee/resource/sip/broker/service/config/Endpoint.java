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

import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RouteHeader;
import java.text.ParseException;

/**
 * Endpoint represents the physical uri of the orchestrated application.
 */
public class Endpoint {
    private final Address endpointAddress;
    private final String asAlias;
    private final String uri;
    private final RouteHeader routeHeader;

    public Endpoint(String uri, String asAlias, AddressFactory addressFactory, HeaderFactory headerFactory)
            throws ParseException {
        this.uri = uri;
        this.asAlias = asAlias;
        endpointAddress = addressFactory.createAddress(addressFactory.createURI(uri));
        // clone the address to apply LR param (only in Route header)
        routeHeader = headerFactory.createRouteHeader((Address) endpointAddress.clone());
        ((SipURI) routeHeader.getAddress().getURI()).setLrParam();
    }

    public String getAsAlias() {
        return asAlias;
    }

    public String getUriString() {
        return uri;
    }

    public RouteHeader getRouteHeader() {
        return routeHeader;
    }

    public Address getEndpointAddress() {
        // clone the address, clients might modify the uri by adding parameters, etc
        return (Address) endpointAddress.clone();
    }

    @Override
    public String toString() {
        return "AS:" + getAsAlias() + ",EP:" + getUriString();
    }

}

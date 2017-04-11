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

/**
 * Keeps all SIP Broker Configuration parameters for this entity/instance.
 */
public class BrokerConfiguration {

    private String transport;
    private String brokerHostname;
    private int port;
    private String imScfHost;
    private int imScfPort;
    private int retransmitTimer;

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getBrokerHostname() {
        return brokerHostname;
    }

    public void setBrokerHostname(String brokerHostname) {
        this.brokerHostname = brokerHostname;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getImScfHost() {
        return imScfHost;
    }

    public void setImScfHost(String imScfHost) {
        this.imScfHost = imScfHost;
    }

    public int getImScfPort() {
        return imScfPort;
    }

    public void setImScfPort(int imScfPort) {
        this.imScfPort = imScfPort;
    }

    public int getRetransmitTimer() {
        return retransmitTimer;
    }

    public void setRetransmitTimer(int retransmitTimer) {
        this.retransmitTimer = retransmitTimer;
    }
}

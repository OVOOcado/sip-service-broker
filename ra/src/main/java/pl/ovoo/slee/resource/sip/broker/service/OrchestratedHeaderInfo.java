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
package pl.ovoo.slee.resource.sip.broker.service;

/**
 * Wrapper for orchestrated message header info.
 */
public class OrchestratedHeaderInfo {

    // uniquely identifies orchestrated session
    private final String sessionId;
    // identifies orchestration rulesets
    private final String servicekey;
    // indicates if this is an originating (or terminating model)
    private final boolean isOriginating;


    /**
     *
     * @param sessionId    - uniquely identifies the session
     * @param servicekey   - servicekey to select orchestrated rulesets
     * @param originating  - indicates if this is originating or terminating case
     */
    public OrchestratedHeaderInfo(String sessionId, String servicekey, boolean originating) {
        this.sessionId = sessionId;
        this.servicekey = servicekey;
        this.isOriginating = originating;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getServicekey() {
        return servicekey;
    }

    public boolean isOriginating() {
        return isOriginating;
    }

    @Override
    public String toString(){
        return "OrchestrationInfo: " + "sessionId: " + sessionId + ", servicekey: " + servicekey + ", orig: "
                + isOriginating;
    }

}

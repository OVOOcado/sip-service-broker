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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Represents ruleset for the orchestrated session.
 */
public class OrchestrationRuleset {

    public enum ErrorLogic {
        SKIP, STOP
    }

    private final String serviceKey;
    private final List<OrchestratedService> applications = new ArrayList<>();
    private final Map<Integer, ErrorLogic> responseToErrorHandling = new HashMap<>();
    private ErrorLogic defaultRulesetHandling = ErrorLogic.STOP;

    public OrchestrationRuleset(String key) {
        serviceKey = key;
    }

    public void appendApplication(OrchestratedService as) {
        applications.add(as);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Rules key: ").append(serviceKey).append("");
        for (OrchestratedService application : applications) {
            sb.append("->").append(application.getAlias());
        }
        return sb.toString();
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void addErrorResponseHandling(Integer response, ErrorLogic handling){
        responseToErrorHandling.put(response, handling);
    }

    public void setDefaultErrorResponseHandling(ErrorLogic handling){
        defaultRulesetHandling = handling;
    }

    public ErrorLogic getResponseHandling(Integer response){
        ErrorLogic handling = responseToErrorHandling.get(response);
        if(handling != null){
            return handling;
        }
        return defaultRulesetHandling;
    }

    public Iterator<OrchestratedService> getServicesIterator(){
        return applications.iterator();
    }

}

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

import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestrationRuleset;
import pl.ovoo.slee.resource.sip.broker.service.eventqueue.SessionEventHandler;
import pl.ovoo.slee.resource.sip.broker.service.sessionfsm.InitialState;
import pl.ovoo.slee.resource.sip.broker.service.sessionfsm.SessionContext;
import pl.ovoo.slee.resource.sip.broker.service.sessionfsm.State;

import java.util.EventObject;

/**
 * This class represents the persistent orchestrated session.
 * It is created at initial INVITE and ends when no more requests/responses are expected.
 * Session has the following responsibilities:
 * - need to know the current AS where request/response has been sent
 * - need to know next AS (or IM-SCF) in the chain (forward and backward direction)
 * - forward request/response to the next ASHandler
 * - cease the chaining when necessary
 * - remove the ASHandler from the chain
 */
public class OrchestratedSession extends SessionEventHandler {
    public final OrchestratedHeaderInfo info;
    private final SessionContext sessionContext;
    private State currentSessionState;

    public OrchestratedSession(OrchestratedHeaderInfo info, SipBrokerContext brokerContext, OrchestrationRuleset
            ruleset) {
        this.info = info;
        sessionContext = new SessionContext(brokerContext, this, ruleset);
        logger = sessionContext.getSessionLogger(getClass());
        currentSessionState = new InitialState(this);

        initSessionTasks();
    }

    public String getID() {
        return info.getSessionId();
    }

    public Logger getSessionLogger(Class clazz) {
        return sessionContext.getSessionLogger(clazz);
    }

    public SessionContext getSessionContext(){
        return sessionContext;
    }

    /**
     * This is the main entry point to handle any message in current state
     *
     * @param event
     */
    public void handleNextEvent(EventObject event) {
        logger.debug("Handle next event: {} in state: {}", event.getClass().getSimpleName(),
                    currentSessionState.getClass().getSimpleName());

        State nextState = currentSessionState.handleEvent(event);
        if (nextState == null) {
            logger.debug("No more processing of the events, this was the last state");
        } else {
            currentSessionState = nextState;
            logger.debug("Event processing finished, proceeding to state: {}",
                                        currentSessionState.getClass().getSimpleName());
        }


    }

    public String toString() {
        return "Session ID: " + getID();
    }

}

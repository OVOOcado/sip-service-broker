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

import javax.sip.address.SipURI;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionManager keeps track of the ongoing sessions.
 */
public class SessionManager {

    /*
     * This is the map with ongoing orchestration sessions
     * The sessions are identified by unique sessionId parameter fetched from the initial INVITE
     */
    private final Map<String, SessionEventHandler> id2SessionMap = new ConcurrentHashMap<>();
    private final SipBrokerContext brokerContext;
    private final Logger logger;



    public SessionManager(SipBrokerContext brokerContext) {
        this.brokerContext = brokerContext;
        logger = brokerContext.getLogger(getClass());
    }


    /**
     * Returns existing session for given sessionId.
     *
     * @param sessionId - id of the session to find
     * @return session handler or null if no corresponding session exists
     */
    public SessionEventHandler findSession(String sessionId) {
        logger.debug("Trying to lookup session for sessionId: {}", sessionId);

        return id2SessionMap.get(sessionId);
    }

    /**
     * Creates session object for given orchestrated information (encodeuri, x-servicekey).
     *
     * @param info - orchestrated info
     * @return orchestrated session handler
     */
    public SessionEventHandler createOrchestratedSession(OrchestratedHeaderInfo info) {
        logger.debug("Creating new session for encodeuri: {}", info.getSessionId());

        OrchestrationRuleset ruleset = brokerContext.getOrchestrationConfig().getRulesForKey(info.getServicekey());
        return id2SessionMap.computeIfAbsent(info.getSessionId(),
                                        k -> new OrchestratedSession(info, brokerContext, ruleset));
    }

    /**
     * Creates auxiliary session that handles non-orchestrated dialogs.
     *
     * @param callId callId identifying session
     * @return auxiliary session event handler
     */
    public SessionEventHandler createAuxiliarySession(String callId) {
        logger.debug("Creating new session for callId: {}", callId);

        SessionEventHandler auxSession = new AuxiliarySession(callId, brokerContext);
        id2SessionMap.put(callId, auxSession);
        return auxSession;
    }

    /**
     * Creates session object for ping aliases read from the request uri
     *
     * @param uri - uri that contains aliases to ping
     * @return ping session event handler
     */
    public SessionEventHandler createPingSession(SipURI uri) {
        logger.debug("Creating new session for ping aliases: {}", uri.getUser());

        PingSession pingSession = new PingSession(uri, brokerContext);

        id2SessionMap.put(uri.getUser(), pingSession);
        return pingSession;
    }

    public void printManagerStatus(){
        if(logger.isDebugEnabled()){
            String sb = "\nSessionManagerStats\nid2Sessions: " + id2SessionMap.size();
            logger.debug(sb);
        }
    }

    /**
     * Deletes session information. No more processing expected for given sessionId.
     *
     * @param sessionId ID of the session
     */
    public void removeSession(String sessionId) {
        logger.trace("Removing session for id: {}", sessionId);
        id2SessionMap.remove(sessionId);
    }

    public void removeAllSessions() {
        logger.info("Removing all pending sessions");
        Iterator<String> it = id2SessionMap.keySet().iterator();
        while(it.hasNext()){
            logger.info("Pending session key: {}", it.next());
        }
        id2SessionMap.clear();
    }

}

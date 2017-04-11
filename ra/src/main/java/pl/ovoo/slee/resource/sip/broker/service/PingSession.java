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
import pl.ovoo.slee.resource.sip.broker.dispatcher.ServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.config.Endpoint;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestratedService;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestrationConfig;
import pl.ovoo.slee.resource.sip.broker.service.eventqueue.SessionEventHandler;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;
import pl.ovoo.slee.resource.sip.broker.utils.SipBrokerLogger;

import javax.sip.ClientTransaction;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.TimeoutEvent;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.EventObject;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * This is the ping session handler.
 */
public class PingSession extends SessionEventHandler {
    // default 500ms retransmit timer is too long,
    // we need to ping several endpoints before incoming OPTIONS timeouts at its client side
    private static final int PING_RETRANSMIT_TIMER = 50;
    private static final String ALIAS_SEPARATOR = "_";

    private final SipBrokerContext brokerContext;
    private final String id;
    private final ServiceProvider imScfProvider;

    private Request pingOptionsRequest;
    private SortedMap<String, List<Endpoint>> alias2ListOfEndpoints; // sorted map preserves the ping aliases order
    private String currentAlias;
    private Iterator<String> aliasesIterator;
    private Iterator<Endpoint> endpointsIterator;

    public PingSession(SipURI uri, SipBrokerContext brokerContext) {
        this.brokerContext = brokerContext;
        imScfProvider = brokerContext.externalServiceProvider;
        id = uri.getUser();
        logger = getSessionLogger(this.getClass());
        initSessionTasks();
    }


    /**
     * This is the main entry point to handle any message in current state
     *
     * @param event - event from the listener
     */
    public void handleNextEvent(EventObject event) {
        logger.debug("Handle ping Request event: {}", event.getClass().getSimpleName());

        if (event instanceof RequestEvent) {
            if (pingOptionsRequest != null) {
                // already handling request by this session
                // this should never happen actually
                sendErrorResponseAndUnmapSession(Response.BAD_REQUEST, ((RequestEvent) event).getRequest(),
                        "Unexpected Request for SIP OPTIONS session");
                return;
            }

            handleRequest(((RequestEvent) event).getRequest());

        } else if (event instanceof ResponseEvent) {

            handleResponse((ResponseEvent) event);

        } else if (event instanceof TimeoutEvent) {
            // this must be client transaction
            logger.trace("Transaction timeout when ping current endpoint, trying with next one");
            if (pingServiceEndpoints()) {
                // current service failed, response already sent by pingServiceEndpoints
                sendErrorResponseAndUnmapSession(Response.NOT_FOUND, pingOptionsRequest, "No endpoint available for "
                        + "alias: " + currentAlias);
            }
        } else {
            // ignore rest stuff, cannot do anything with these events
            logger.trace("Received event: {}, no action necessary.", event.getClass().getSimpleName());
        }
    }

    private void handleRequest(Request request) {
        logger.trace("handleRequest");

        // now store the incoming request
        pingOptionsRequest = request;

        if (!parseUri(pingOptionsRequest.getRequestURI())) {
            sendErrorResponseAndUnmapSession(Response.BAD_REQUEST, pingOptionsRequest, "URI does not indicate  " +
                    "aliases to ping.");
        } else {

            aliasesIterator = alias2ListOfEndpoints.keySet().iterator();

            if (aliasesIterator.hasNext()) {
                currentAlias = aliasesIterator.next();
                logger.debug("Ping session. Trying to ping alias: {}", currentAlias);

                // point endpointsIterator to the next service
                endpointsIterator = alias2ListOfEndpoints.get(currentAlias).iterator();

                if (pingServiceEndpoints()) {
                    // current service failed, response already sent by pingServiceEndpoints
                    sendErrorResponseAndUnmapSession(Response.NOT_FOUND, pingOptionsRequest, "No endpoint available "
                            + "for alias: " + currentAlias);
                }

            } else {
                sendErrorResponseAndUnmapSession(Response.BAD_REQUEST, pingOptionsRequest, "No alias to ping.");
            }
        }
    }

    private void handleResponse(ResponseEvent event) {
        logger.trace("handleResponse");
        if (event.getResponse().getStatusCode() == Response.OK) {
            logger.debug("Received 200 OK response for: {}", currentAlias);

            if (aliasesIterator.hasNext()) {
                currentAlias = aliasesIterator.next();
                logger.debug("Ping session. Trying to ping alias: {}", currentAlias);
                // point endpointsIterator to the next service
                endpointsIterator = alias2ListOfEndpoints.get(currentAlias).iterator();

                if (pingServiceEndpoints()) {
                    // current service failed, response already sent by pingServiceEndpoints
                    sendErrorResponseAndUnmapSession(Response.BAD_REQUEST, pingOptionsRequest, "No endpoint " +
                            "available" + " for alias: " + currentAlias);
                }

            } else {
                logger.trace("All alias2ListOfEndpoints reachable, returning success to IM-SCF");

                sendSuccessResponseAndUnmapSession();
            }
        } else {
            logger.trace("Error response from current endpoint, trying with next one");
            if (pingServiceEndpoints()) {
                // current service failed, response already sent by pingServiceEndpoints
                sendErrorResponseAndUnmapSession(Response.NOT_FOUND, pingOptionsRequest, "No endpoint available for "
                        + "alias: " + currentAlias);
            }
        }
    }


    /**
     * Get current service endpoints and try to ping one
     * Returns true in case of total failure (no endpoint to ping)
     *
     * @return true if failed to send OPTIONS request, false otherwise
     */
    private boolean pingServiceEndpoints() {
        while (endpointsIterator.hasNext()) {
            Endpoint ep = endpointsIterator.next();
            try {
                sendPingRequest(ep);
                //return false and wait for response (or timeout)
                return false;

            } catch (ParseException | SipException | InvalidArgumentException e) {
                logger.warn("Unable to send OPTIONS request, continue with next endpoint", e);
                // continue to next endpoint
            }
        }
        // still here?
        // no OPTIONS sent to current service, return true
        logger.trace("Failed to ping current service, no endpoint worked");
        return true;
    }


    /**
     * Sends SIP OPTIONS request towards the given endpoint
     *
     * @param nextEndpoint endpoint to ping
     */
    private void sendPingRequest(Endpoint nextEndpoint) throws ParseException, InvalidArgumentException, SipException {
        Request optionsRequest = MessageUtils.createOptionsRequest(brokerContext, nextEndpoint.getEndpointAddress(),
                                imScfProvider.getNewCallId(), logger);
        ClientTransaction ct = imScfProvider.getNewClientTransaction(optionsRequest);
        ct.setRetransmitTimer(PING_RETRANSMIT_TIMER);
        ct.sendRequest();
        // associate transaction with handler
        ct.setApplicationData(itsReferenceWrapper);
    }


    /**
     * Sends error response towards the calling node.
     * Removes current session from the map
     */
    private void sendErrorResponseAndUnmapSession(int statusCode, Request request, String reason) {
        try {
            Response response = brokerContext.messageFactory.createResponse(statusCode, request);
            if (reason != null) {
                response.setReasonPhrase(reason);
            }
            imScfProvider.sendResponse(response);

        } catch (Exception e) {
            logger.warn("Ping session. Error while sending error response to IM-SCF", e);
        } finally {
            // remove current session from the map
            brokerContext.getSessionManager().removeSession(id);
            brokerContext.getUsageParameters().incrementPingSessionsErrorCount(1);
        }
    }

    /**
     * Sends success response towards the calling node.
     * Removes current session from the map
     */
    private void sendSuccessResponseAndUnmapSession() {
        try {
            Response response = brokerContext.messageFactory.createResponse(Response.OK, pingOptionsRequest);
            imScfProvider.sendResponse(response);
            brokerContext.getUsageParameters().incrementPingSessionsSuccessCount(1);
        } catch (Exception e) {
            logger.warn("Ping session. Error while sending success response to IM-SCF", e);
            brokerContext.getUsageParameters().incrementPingSessionsErrorCount(1);
        } finally {
            // remove current session from the map
            brokerContext.getSessionManager().removeSession(id);
        }
    }


    /**
     * Parses SIP URI to find the list of aliases.
     *
     * @param pingUri URI to parse
     * @return true if successful, false if no alias found or not SIP URI
     */
    private boolean parseUri(URI pingUri) {
        alias2ListOfEndpoints = new TreeMap<>();

        if (pingUri.isSipURI()) {
            SipURI sipuri = (SipURI) pingUri;
            String user = sipuri.getUser();
            if (user.length() == 0) {
                logger.warn("Ping session. User not found in OPTIONS URI.");
                return false;
            }

            String[] aliases = user.trim().split(ALIAS_SEPARATOR);
            OrchestrationConfig orchestrationConfig = brokerContext.getOrchestrationConfig();
            for (String alias : aliases) {
                OrchestratedService service = orchestrationConfig.getApplicationServiceForAlias(alias);

                if (service == null) {
                    logger.warn("Ping session. No alias configured with given name: {}", alias);
                    // returning false because it is clear that this alias will not give success response
                    return false;
                }

                List<Endpoint> endpoints = service.getEndpoints();
                if (endpoints.isEmpty()) {
                    logger.warn("Ping session. No endpoints defined for alias: {}", alias);
                    // returning false because it is clear that this alias will not give success response
                    return false;
                }

                logger.debug("Ping session. Add alias to alias2ListOfEndpoints list: {}", alias);
                alias2ListOfEndpoints.put(alias, endpoints);
            }

            if (alias2ListOfEndpoints.size() > 0) {
                return true;
            } else {
                logger.warn("Ping session. Not possible to find configured endpoints to ping for: {}", sipuri);
                return true;
            }
        } else {
            return false;
        }
    }


    public String getID() {
        return id;
    }

    /**
     * Returns SipBrokerLogger instance.
     * Use this within session context in order to keep particular session traceable.
     */
    public Logger getSessionLogger(Class clazz) {
        return new SipBrokerLogger(brokerContext.getTracer(clazz), id);
    }

}

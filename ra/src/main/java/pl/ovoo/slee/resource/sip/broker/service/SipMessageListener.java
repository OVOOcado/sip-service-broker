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

import gov.nist.javax.sip.DialogTimeoutEvent;
import gov.nist.javax.sip.ResponseEventExt;
import gov.nist.javax.sip.SipListenerExt;
import gov.nist.javax.sip.header.CallID;
import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.dispatcher.InternalServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.eventqueue.EventsQueue;
import pl.ovoo.slee.resource.sip.broker.service.eventqueue.SessionEventHandler;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ExtensionHeader;
import javax.sip.header.Parameters;
import javax.sip.header.RouteHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.ListIterator;

/**
 * SipMessageListener receives all requests and responses from the SIP stack.
 * It performs initial preprocessing in order to find and forward message to its corresponding session.
 *
 */
public class SipMessageListener implements SipListenerExt {

    private static final String X_SERVICEKEY = "x-servicekey";
    private static final String ORIG = "orig";
    private static final String TERM = "term";
    private static final String X_MRF = "x-mrf";
    private static final String X_ICA = "x-ica";
    private Logger logger;
    private SessionManager sessionManager;
    private SipBrokerContext brokerContext;
    // events queue to process all incoming events: requests, responses, timeouts, etc.
    // synchronized on session level
    private EventsQueue eventsQueue;


    /**
     * Listener initialization. Could not be done in constructor due to reference dependencies.
     *
     * @param brokerContext           - SIP Broker context
     * @param queueMaxSize            - the capacity of this queue
     * @param queueInitialThreads     - the number of threads to keep in the pool
     * @param queueMaxThreads         - the maximum number of threads to allow in the pool
     * @param queueThreadKeepAlive    - maximum time that excess idle threads will wait for new tasks before terminating
     */
    public void initListener(SipBrokerContext brokerContext,
                             int queueMaxSize, int queueInitialThreads,
                             int queueMaxThreads, int queueThreadKeepAlive) {
        logger = brokerContext.getLogger(getClass());
        sessionManager = brokerContext.getSessionManager();
        this.brokerContext = brokerContext;

        eventsQueue = new EventsQueue(brokerContext, queueMaxSize, queueInitialThreads,
                                        queueMaxThreads, queueThreadKeepAlive);
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////
    // Begin SipListenerExt methods implementation
    public void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        logger.debug("Enter processRequest:\n{}", request);

        if (request.getMethod().equals(Request.INVITE)) {
            processInvite(requestEvent);

        } else {
            // non-INVITE requests
            if (request.getMethod().equals(Request.OPTIONS)) {
                processOptionsRequest(requestEvent);

            } else {
                processCommonDialogRequest(requestEvent);

            }
        }
        logger.trace("exit processRequest");
    }

    private void processCommonDialogRequest(RequestEvent requestEvent) {
        // request other than INVITE or OPTIONS, find session handler from dialog data
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) requestEvent.getDialog().getApplicationData();
        SessionEventHandler sessionHandler = wrapper.getSessionHandler();

        if (sessionHandler == null) {
            logger.trace("No session for this non-INVITE request, dropping.");

        } else {
            logger.debug("Found session for this request, continue processing");
            eventsQueue.enqueueEvent(requestEvent, sessionHandler);
        }
    }

    private void processOptionsRequest(RequestEvent requestEvent) {
        logger.debug("SIP OPTIONS request, instantiate ping session.");

        if (!requestEvent.getRequest().getRequestURI().isSipURI()) {
            sendImmediateErrorResponse(Response.BAD_REQUEST, requestEvent,
                    "Only SIP URI supported in OPTIONS Request URI");
            return;
        }

        SipURI optionsSipUri = (SipURI) requestEvent.getRequest().getRequestURI();
        SessionEventHandler pingSession = sessionManager.findSession(optionsSipUri.getUser());
        if (pingSession != null) {
            logger.debug("Session already handled for aliases: {}, ignoring", optionsSipUri.getUser());
            return;
        }

        pingSession = sessionManager.createPingSession(optionsSipUri);
        eventsQueue.enqueueEvent(requestEvent, pingSession);
        brokerContext.getUsageParameters().incrementPingSessionsStarted(1);
    }


    private void processInvite(RequestEvent requestEvent) {
        if (isAuxiliarySession(requestEvent)) {
            processAuxiliarySession(requestEvent);
        } else {
            try {
                processOrchestratedSession(requestEvent);
            } catch (ParseException e) {
                logger.debug("Not possible to create orchestrated session", e);
                sendImmediateErrorResponse(Response.BAD_REQUEST, requestEvent, e.getMessage());
            }
        }
    }

    /**
     * Checks if this is an auxiliary session.
     * Auxiliary session contains special header that indicates no orchestration for this INVITE.
     *
     * @param requestEvent - request event
     *
     * @return true if this is auxiliary session
     */
    private boolean isAuxiliarySession(RequestEvent requestEvent){
        boolean auxiliarySessionHeaderPresent = false;
        ExtensionHeader xMrf = (ExtensionHeader) requestEvent.getRequest().getHeader(X_MRF);
        if (xMrf != null && "true" .equals(xMrf.getValue())) {
            auxiliarySessionHeaderPresent = true;
        } else {
            ExtensionHeader xIca = (ExtensionHeader) requestEvent.getRequest().getHeader(X_ICA);
            if (xIca != null && "true" .equals(xIca.getValue())) {
                auxiliarySessionHeaderPresent = true;
            }
        }

        return auxiliarySessionHeaderPresent;
    }

    /**
     * This process auxiliary session event, it fetch or create new session
     *
     * @param event - Request event
     *
     * @throws ParseException when not possible to parse the request orchestration data
     */
    private void processOrchestratedSession(RequestEvent event) throws ParseException {
        String pOdid = MessageUtils.getCreateOriginalDialogId(event.getRequest(), brokerContext.headerFactory);
        SessionEventHandler orchestratedSession = sessionManager.findSession(pOdid);
        if (orchestratedSession == null) {
            OrchestratedHeaderInfo info = readHeaderInfo(event.getRequest(), pOdid);
            orchestratedSession = sessionManager.createOrchestratedSession(info);
        } else {
            logger.debug("Found session for this request, continue processing");
        }
        eventsQueue.enqueueEvent(event, orchestratedSession);
    }

    /**
     * This process auxiliary session event, it fetch or create new session
     *
     * @param event - Request event
     */
    private void processAuxiliarySession(RequestEvent event) {
        // this is a special INVITE from AS, handle auxiliary session
        logger.debug("Found special headers, processing auxiliary session");

        String callId = ((CallIdHeader) event.getRequest().getHeader(CallID.NAME)).getCallId();
        SessionEventHandler auxSession = sessionManager.findSession(callId);
        if (auxSession != null) {
            // retransmission, dropping
            logger.trace("Session already handled for callId: {}", callId);
            return;
        }

        SessionEventHandler auxiliarySession = sessionManager.createAuxiliarySession(callId);
        eventsQueue.enqueueEvent(event, auxiliarySession);
        brokerContext.getUsageParameters().incrementAuxSessionsCount(1);
        brokerContext.getUsageParameters().incrementRunningAuxSessionsCount(1);

        logger.debug("Auxiliary session event enqueued");
    }


    public void processResponse(ResponseEvent responseEvent) {
        logger.debug("Enter handleResponse:\n{}", responseEvent.getResponse());

        if (((ResponseEventExt) responseEvent).isRetransmission()) {
            logger.trace("Retransmission, drop the response: {}",
                        responseEvent.getDialog().getCallId());
            return;
        }

        // find session handler from client transaction
        ClientTransaction ctx = responseEvent.getClientTransaction();
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) ctx.getApplicationData();
        SessionEventHandler sessionEventHandler = wrapper.getSessionHandler();
        if (sessionEventHandler == null) {
            logger.debug("Unexpected response, no handler found, no session, discarding it");
            return;
        } else {
            logger.debug("Found session handler: {}", sessionEventHandler);
        }

        eventsQueue.enqueueEvent(responseEvent, sessionEventHandler);
    }


    public void processTimeout(TimeoutEvent timeoutEvent) {
        logger.debug("Enter processTimeout timeout: {}", timeoutEvent.getTimeout());

        Object appData;
        if (timeoutEvent.isServerTransaction()) {
            // find session handler from server transaction
            ServerTransaction st = timeoutEvent.getServerTransaction();
            appData = st.getApplicationData();
        } else {
            // find session handler from client transaction
            ClientTransaction ctx = timeoutEvent.getClientTransaction();
            appData = ctx.getApplicationData();
        }

        if (appData == null) {
            logger.trace("Unexpected response, no handler found, no session, discarding event");
            return;
        }

        SessionEventHandler sessionEventHandler = ((HandlerReferenceWrapper) appData).getSessionHandler();
        logger.debug("Found session handler: {}", sessionEventHandler);
        eventsQueue.enqueueEvent(timeoutEvent, sessionEventHandler);
    }

   /**
    * From SipListenerExt javadoc:
    * This Event notifies the application that a dialog Timer expired in the
    * Dialog's state machine.
    * Applications implementing this method should take care of sending the BYE or terminating
    * the dialog to avoid any dialog leaks.
    */
    public void processDialogTimeout(DialogTimeoutEvent dialogTimeoutEvent) {
        logger.debug("Enter processDialogTimeout dialogId: {}, reason: {}", dialogTimeoutEvent.getDialog().getCallId(),
                                                                            dialogTimeoutEvent.getReason());
        Dialog dialog  = dialogTimeoutEvent.getDialog();

        // find associated session
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) dialog.getApplicationData();
        SessionEventHandler sessionHandler = wrapper.getSessionHandler();

        eventsQueue.enqueueEvent(dialogTimeoutEvent, sessionHandler);
    }

    public void processIOException(javax.sip.IOExceptionEvent ioExceptionEvent) {
        logger.warn("IOException occurred: {}", ioExceptionEvent);
    }


    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
        logger.trace("Enter processTransactionTerminated");

        if (transactionTerminatedEvent.isServerTransaction()) {
            logger.debug("ServerTransaction terminated, branchID: {}",
                                             transactionTerminatedEvent.getServerTransaction().getBranchId());
        }
    }


    public void processDialogTerminated(javax.sip.DialogTerminatedEvent dialogTerminatedEvent) {
        logger.debug("Enter processDialogTerminated dialogTerminatedEvent: {}", dialogTerminatedEvent.getDialog().getCallId());

        Dialog dialog = dialogTerminatedEvent.getDialog();

        // find associated session
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) dialog.getApplicationData();
        SessionEventHandler sessionHandler = wrapper.getSessionHandler();

        eventsQueue.enqueueEvent(dialogTerminatedEvent, sessionHandler);
    }
    // End SipListenerExt methods implementation
    ////////////////////////////////////////////////////////////////////////////////////////////////////


    /**
     * Reads orchestrated data from the INVITE request.
     *
     * @param request - INVITE request
     * @param pOdid   - P-Original-Dialog-ID
     *
     * @throws ParseException - when not possible to read orchestrated information
     */
     OrchestratedHeaderInfo readHeaderInfo(Request request, String pOdid) throws ParseException {
        // iterate over Route headers to fetch the one containing encodeuri
        ListIterator headers = request.getHeaders(RouteHeader.NAME);
        while (headers.hasNext()) {
            RouteHeader route = (RouteHeader) headers.next();
            URI uri = route.getAddress().getURI();

            // This can be either SIP URI or TEL URI
            Parameters parametrizedUri = (Parameters) uri;
            String serviceKey = parametrizedUri.getParameter(X_SERVICEKEY);
            if (serviceKey != null) {
                String origString = parametrizedUri.getParameter(ORIG);
                String termString = parametrizedUri.getParameter(TERM);

                if (origString == null && termString == null) {
                    logger.warn("Inconsistent Route URI parameters {}", parametrizedUri.toString());
                    continue;
                }

                boolean isOrig = origString != null;

                return new OrchestratedHeaderInfo(pOdid, serviceKey, isOrig);
            }
        }
        throw new ParseException("Missing orchestration data in request", 0);
    }


    private void sendImmediateErrorResponse(int statusCode, RequestEvent requestEvent, String reason) {
        try {
            Response response = brokerContext.messageFactory.createResponse(statusCode, requestEvent.getRequest());
            if(reason != null){
                response.setReasonPhrase(reason);
            }
            if(requestEvent.getSource() instanceof InternalServiceProvider){
                brokerContext.internalServiceProvider.sendResponse(response);
            } else {
                brokerContext.externalServiceProvider.sendResponse(response);
            }
        } catch (Exception e) {
            logger.warn("Unable to send error response: {}", e);
        }
    }
}

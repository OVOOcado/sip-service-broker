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
import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.dispatcher.InternalServiceProvider;
import pl.ovoo.slee.resource.sip.broker.dispatcher.ServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.eventqueue.SessionEventHandler;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;
import pl.ovoo.slee.resource.sip.broker.utils.SipBrokerLogger;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.TimeoutEvent;
import javax.sip.header.CSeqHeader;
import javax.sip.header.RequireHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.ListIterator;

/**
 * This is the auxiliary session for handling non-orchestrated dialogs from ASs.
 */
public class AuxiliarySession extends SessionEventHandler {
    private static final String TAG_100_REL = "100rel";
    private static final int MAX_LOGGER_ID_LENGTH = 10; // 10 chars should be enough to distinguish session for tracing
    private final SipBrokerContext brokerContext;
    private final String sessionId;
    private Request lastIncomingRequest;
    private Request lastOutgoingInvite;
    private Request infoRequest;
    private Request prackRequest;
    private ServerTransaction lastServerTransaction;
    private ServerTransaction infoServerTransaction;
    private ServerTransaction prackServerTransaction;
    private Response lastSessionProgressResponse;

    private Dialog incomingDialog;
    private Dialog outgoingDialog;

    private ServiceProvider incomingAppProvider;
    private final ServiceProvider imScfProvider;


    public AuxiliarySession(String callId, SipBrokerContext brokerContext) {
        this.sessionId = callId;
        this.brokerContext = brokerContext;
        imScfProvider = brokerContext.externalServiceProvider;
        logger = getSessionLogger(this.getClass());
        initSessionTasks();
    }


    @Override
    public void handleNextEvent(EventObject event) {

        if (event instanceof RequestEvent) {

            try {
                handleRequest((RequestEvent) event);
            } catch (UnrecoverableError e) {
                logger.error("Unrecoverable error occurred for request.", e);
            }

        } else if (event instanceof ResponseEvent) {

            try {
                handleResponse((ResponseEvent) event);
            } catch (SendResponseError e) {
                logger.error("Unrecoverable error occurred for response.", e);
            }

        } else if (event instanceof TimeoutEvent) {
            logger.trace("Received TimeoutEvent");

            try {
                handleTransactionTimeout((TimeoutEvent) event);
            } catch (SendResponseError e) {
                logger.error("Unrecoverable error occurred for timeout.", e);
            }

        } else if (event instanceof DialogTimeoutEvent) {
            logger.trace("Received DialogTimeoutEvent");

            Dialog dialog = ((DialogTimeoutEvent) event).getDialog();
            checkDialogAndRemoveSession(dialog);

        } else if (event instanceof DialogTerminatedEvent) {
            logger.trace("Received DialogTerminatedEvent");

            Dialog dialog = ((DialogTerminatedEvent) event).getDialog();
            checkDialogAndRemoveSession(dialog);

        } else {
            throw new UnsupportedOperationException("Unsupported event: " + event.getClass().getName());
        }
    }


    private void handleTransactionTimeout(TimeoutEvent timeoutEvent) throws SendResponseError {
        logger.trace("handleTransactionTimeout");
        if (timeoutEvent.isServerTransaction()) {
            // server transaction timeouts are unexpected, there is no logic predefined for this
            ServerTransaction st = timeoutEvent.getServerTransaction();
            logger.warn("ServerTransaction timeout for request {}", st.getRequest().getMethod());
            throw new SendResponseError("Unexpected ServerTransaction timeout for " + st.getRequest());
        } else {
            Request req = timeoutEvent.getClientTransaction().getRequest();
            // client transaction timeout for INVITE, treat as 408 (Request Timeout)
            if(req.getMethod().equals(Request.INVITE)){
                try {
                    // INVITE failed, remove dialog
                    checkDialogAndRemoveSession(timeoutEvent.getClientTransaction().getDialog());
                    respondToPendingRequestsOnDialogTerminatingResponse();
                    Response errorResponse = createNewResponse(Response.REQUEST_TIMEOUT, req);
                    lastServerTransaction.sendResponse(errorResponse);
                    brokerContext.getUsageParameters().incrementAbortedAuxSessionsCount(1);
                } catch (InvalidArgumentException | SipException e) {
                    logger.warn("Error while sending error response", e);
                    brokerContext.getUsageParameters().incrementAbortedAuxSessionsCount(1);
                }
            }
        }
    }

    private void handleResponse(ResponseEvent event) throws SendResponseError {
        logger.debug("handleResponse, statusCode: {}", event.getResponse().getStatusCode());

        Response response = event.getResponse();
        if (response.getStatusCode() == Response.SESSION_PROGRESS) {
            processResponseSessionProgress(event);

        } else if (response.getStatusCode() == Response.TRYING) {
            logger.debug("Received provisional response: {}, no action.", response.getStatusCode());

        } else if (response.getStatusCode() >= Response.MULTIPLE_CHOICES) {
            logger.debug("Received error response: {}, forwarding.", response.getStatusCode());

            try {
                respondToPendingRequestsOnDialogTerminatingResponse();
            } catch (SipException | InvalidArgumentException e) {
                logger.warn("Error while sending terminating responses", e);
            }
            processResponseForward(event);

        } else {
            // any other response to forward to the other side
            processResponseForward(event);
        }
    }

    /**
     * Responds to pending INFO/PRACK requests.
     * Should be used in conjunction with error response sent on this dialog.
     */
    private void respondToPendingRequestsOnDialogTerminatingResponse() throws SipException, InvalidArgumentException {
        logger.trace("respondToPendingRequestsOnDialogTerminatingResponse");

        if(infoRequest != null){
            logger.debug("Found pending INFO request transaction, send response");
            infoServerTransaction.sendResponse(createNewResponse(Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST,
                                                                    infoRequest));
            infoServerTransaction = null;
            infoRequest = null;
        }

        if(prackRequest != null){
            logger.debug("Found pending PRACK request transaction, send response");
            prackServerTransaction.sendResponse(createNewResponse(Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST,
                                                                    prackRequest));
            prackServerTransaction = null;
            prackRequest = null;
        }
    }

    /**
     * Creates new response for the request
     *
     * @param statusCode status code
     * @param request request to create response for
     *
     * @return new Response object
     */
    private Response createNewResponse(int statusCode, Request request){
        try {
            return brokerContext.messageFactory.createResponse(statusCode, request);
        } catch (ParseException e) {
            // will not happen if Response constants are used
            throw new IllegalArgumentException("Unexpected response code: " + statusCode, e);
        }
    }

    private void processResponseSessionProgress(ResponseEvent event) throws SendResponseError {
        logger.trace("processResponseSessionProgress");

        Response response = event.getResponse();
        RequireHeader requireHeader = (RequireHeader) response.getHeader(RequireHeader.NAME);
        try {
            if (requireHeader != null && TAG_100_REL.equals(requireHeader.getOptionTag())) {
                logger.debug("Found 100rel in response, this is a reliable response.");
                Response sessionProgress = MessageUtils.createForwardedReliableResponse(response, incomingDialog,
                        brokerContext.getBrokerContactHeader(), logger);
                incomingDialog.sendReliableProvisionalResponse(sessionProgress);
                // store the response for the prack
                lastSessionProgressResponse = response;
            } else {
                logger.debug("Basic provisional response: {}, forwarding back", response.getStatusCode());
                processResponseForward(event);
            }
        } catch (SipException | ParseException | InvalidArgumentException e) {
            logger.error("Unable to send provisional response: {}", e.getMessage());
            throw new SendResponseError("Unable to send provisional response", e);
        }
    }


    private void processResponseForward(ResponseEvent event) throws SendResponseError {
        logger.trace("processResponseForward");

        Response incomingResponse = event.getResponse();
        Dialog dialogToForward;
        if (incomingDialog == event.getDialog()) {
            dialogToForward = outgoingDialog;
        } else {
            dialogToForward = incomingDialog;
        }

        CSeqHeader cseq = (CSeqHeader) incomingResponse.getHeader(CSeqHeader.NAME);
        Request req;
        ServerTransaction st;
        if (cseq.getMethod().equals(Request.INFO)) {
            req = infoRequest;
            st = infoServerTransaction;
            if (incomingResponse.getStatusCode() >= Response.OK) {
                infoRequest = null;
                infoServerTransaction = null;
            }
        } else if (cseq.getMethod().equals(Request.PRACK)) {
            req = prackRequest;
            st = prackServerTransaction;
            if (incomingResponse.getStatusCode() >= Response.OK) {
                prackRequest = null;
                prackServerTransaction = null;
            }
        } else {
            // INVITE or BYE
            req = lastIncomingRequest;
            st = lastServerTransaction;
            if (incomingResponse.getStatusCode() >= Response.OK) {
                lastIncomingRequest = null;
                lastServerTransaction = null;
            }
        }

        if(st == null){
            logger.debug("ServerTransaction already terminated, request not forwarded");
            return;
        }

        try {
            Response newResponse = MessageUtils.createForwardedResponse(incomingResponse, dialogToForward, req,
                    brokerContext.messageFactory, brokerContext.getBrokerContactHeader(), logger);
            st.sendResponse(newResponse);
            st.setApplicationData(itsReferenceWrapper);

            if (Request.BYE.equals(req.getMethod())) {
                brokerContext.getUsageParameters().incrementSuccessfulAuxSessionsCount(1);
            }

        } catch (SipException | ParseException | InvalidArgumentException e) {
            logger.error("Unable to send response: {}", e.getMessage());
            throw new SendResponseError("Unable to send response", e);
        }
    }


    private void handleRequest(RequestEvent event) throws UnrecoverableError {
        Request request = event.getRequest();
        logger.trace("handleRequest:\n{}", request);

        if (request.getMethod().equals(Request.INVITE)) {
            handleInvite(event);
        } else if (request.getMethod().equals(Request.BYE)) {
            handleBye(event);
        } else if (request.getMethod().equals(Request.ACK)) {
            sendAckToNextAs(request);
        } else if (request.getMethod().equals(Request.PRACK)) {
            prackServerTransaction = event.getServerTransaction();
            handlePrack(event);
        } else {
            handleInfoRequest(event);
        }
    }

    /**
     * This method process the sip PRACK Request
     *
     * @param event request event
     */
    private void handlePrack(RequestEvent event) throws UnrecoverableError {
        logger.trace("handlePrack");

        Request incomingPrack = event.getRequest();
        if (checkRetransmission(prackRequest, incomingPrack)) {
            logger.trace("PRACK retransmission, dropping request.");
            return;
        }

        // keep PRACK server transaction and request
        prackServerTransaction = event.getServerTransaction();
        prackRequest = incomingPrack;
        try {
            Request prack = outgoingDialog.createPrack(lastSessionProgressResponse);
            ClientTransaction ct = imScfProvider.getNewClientTransaction(prack);
            outgoingDialog.sendRequest(ct);
            ct.setApplicationData(itsReferenceWrapper);
        } catch (SipException e) {
            throw new UnrecoverableError("Unable to forward PRACK", e);
        }
    }


    private void handleInfoRequest(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleInfoRequest");

        Request incomingRequest = event.getRequest();
        if (checkRetransmission(infoRequest, incomingRequest)) {
            logger.trace("Retransmission, dropping request.");
            return;
        }

        try {
            infoRequest = incomingRequest;
            infoServerTransaction = event.getServerTransaction();
            Dialog dialogToForward;
            ClientTransaction ct;
            if (event.getDialog() == outgoingDialog) {
                logger.trace("Request initiated by IM-SCF");
                dialogToForward = incomingDialog;
                Request newRequest = MessageUtils.createOnDialogRequest(infoRequest, dialogToForward,
                        brokerContext.getBrokerContactHeader(), logger);
                ct = incomingAppProvider.getNewClientTransaction(newRequest);
            } else {
                logger.trace("Request initiated by application");
                dialogToForward = outgoingDialog;
                Request newRequest = MessageUtils.createOnDialogRequest(infoRequest, dialogToForward,
                        brokerContext.getBrokerContactHeader(), logger);
                ct = imScfProvider.getNewClientTransaction(newRequest);
            }

            dialogToForward.sendRequest(ct);
            // associate transaction with handler
            ct.setApplicationData(itsReferenceWrapper);

            logger.trace("Request forwarded");
        } catch (SipException | ParseException e) {
            throw new UnrecoverableError("Unable to forward INFO", e);
        }
    }


    private void handleBye(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleBye");

        Request incomingRequest = event.getRequest();
        if (checkRetransmission(lastIncomingRequest, incomingRequest)) {
            logger.trace("Request retransmission, ignoring.");
            return;
        }

        try {
            lastIncomingRequest = incomingRequest;
            lastServerTransaction = event.getServerTransaction();
            Dialog dialogToForward;
            ClientTransaction ct;
            if (event.getDialog() == outgoingDialog) {
                logger.trace("BYE request initiated by IM-SCF");
                dialogToForward = incomingDialog;
                Request newByeRequest;
                newByeRequest = MessageUtils.createOnDialogRequest(lastIncomingRequest, dialogToForward,
                        brokerContext.getBrokerContactHeader(), logger);
                ct = incomingAppProvider.getNewClientTransaction(newByeRequest);
            } else {
                logger.trace("BYE request initiated by application");
                dialogToForward = outgoingDialog;
                Request newByeRequest = MessageUtils.createOnDialogRequest(lastIncomingRequest, dialogToForward,
                        brokerContext.getBrokerContactHeader(), logger);
                ct = imScfProvider.getNewClientTransaction(newByeRequest);
            }

            dialogToForward.sendRequest(ct);
            // associate transaction with handler
            ct.setApplicationData(itsReferenceWrapper);

            logger.trace("BYE request forwarded");

        } catch (ParseException | SipException e) {
            throw new UnrecoverableError("Unable to forward BYE", e);
        }
    }


    private void handleInvite(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleInvite");

        try {
            if (event.getSource() instanceof InternalServiceProvider) {
                // this is one of JSLEE Services, use internal Sip Provider
                incomingAppProvider = brokerContext.internalServiceProvider;
            } else {
                // external service, use standard Sip Provider
                incomingAppProvider = brokerContext.externalServiceProvider;
            }

            Request incomingRequest = event.getRequest();
            if (checkRetransmission(lastIncomingRequest, incomingRequest)) {
                logger.trace("Request retransmission, ignoring.");
                return;
            }

            lastIncomingRequest = incomingRequest;
            lastServerTransaction = incomingAppProvider.getNewServerTransaction(incomingRequest);
            incomingDialog = lastServerTransaction.getDialog();

            // associate session/handler references
            incomingDialog.setApplicationData(itsReferenceWrapper);

            sendProvisionalResponse(incomingRequest, lastServerTransaction);
            forwardInviteToImscf(incomingRequest);

        } catch (SipException | ParseException e) {
            logger.warn("Unable to send INVITE", e);
            try {
                // for INVITE try to send error response back to AS
                Response response = createNewResponse(Response.SERVICE_UNAVAILABLE, event.getRequest());
                incomingAppProvider.sendResponse(response);
            } catch (SipException e1) {
                // that's really bad
                logger.error("Unable to send error response: {}", e1);
                throw new UnrecoverableError(e1);
            }
        }
    }


    private void forwardInviteToImscf(Request incomingInvite) throws ParseException, SipException {
        logger.trace("handleInvite {}", incomingInvite);

        // pop first route (broker's one)
        incomingInvite.removeFirst(RouteHeader.NAME);
        ListIterator<RouteHeader> incomingRoutes = incomingInvite.getHeaders(RouteHeader.NAME);
        List<RouteHeader> outgoingRoutes = new ArrayList<>();

        // copy rest of the route headers
        while(incomingRoutes.hasNext()){
            outgoingRoutes.add(incomingRoutes.next());
        }

        if (outgoingRoutes.isEmpty()) {
            // empty incoming route headers, use default one
            outgoingRoutes.add(brokerContext.getDefaultImScfRouteHeader());
        }

        lastOutgoingInvite = MessageUtils.createInvite(brokerContext, incomingInvite, outgoingRoutes, logger,
                imScfProvider.getNewCallId());
        ClientTransaction ct = imScfProvider.getNewClientTransaction(lastOutgoingInvite);
        ct.sendRequest();

        // INVITE request sent, there is a dialog to store
        outgoingDialog = ct.getDialog();

        // associate transaction and session/handler with handler
        ct.setApplicationData(itsReferenceWrapper);
        outgoingDialog.setApplicationData(itsReferenceWrapper);

        logger.trace("INVITE sent to the IM-SCF");
    }


    private void sendAckToNextAs(Request receivedAck) throws UnrecoverableError {
        logger.debug("ACK to INVITE OK, send ACK to IM-SCF");

        try {
            long cseqNumber = ((CSeqHeader) lastOutgoingInvite.getHeader(CSeqHeader.NAME)).getSeqNumber();
            Request ack = MessageUtils.createAck(receivedAck, cseqNumber, outgoingDialog, brokerContext
                    .getBrokerContactHeader(), logger);

            outgoingDialog.sendAck(ack);

        } catch (ParseException | SipException | InvalidArgumentException e) {
            throw new UnrecoverableError("Unable to forward ACK", e);
        }
    }


    @Override
    public String getID() {
        return sessionId;
    }

    @Override
    public Logger getSessionLogger(Class clazz) {
        String loggerSessionId = sessionId;
        if (sessionId.length() > MAX_LOGGER_ID_LENGTH) {
            loggerSessionId = sessionId.substring(0, MAX_LOGGER_ID_LENGTH);
        }
        return new SipBrokerLogger(brokerContext.getTracer(clazz), loggerSessionId);
    }


    /**
     * Successful dialogs cleanup and session removal method
     *
     * @param dialog
     */
    private void checkDialogAndRemoveSession(Dialog dialog) {
        logger.trace("checkDialogAndRemoveSession");

        if (outgoingDialog == null && incomingDialog == null) {
            logger.trace("Both dialogs are null, nothing to do");
            return;
        }

        if (incomingDialog != null && dialog.getCallId().equals(incomingDialog.getCallId())) {
            logger.trace("Dialog end from AS");
            incomingDialog = null;
        }

        if (outgoingDialog != null && dialog.getCallId().equals(outgoingDialog.getCallId())) {
            logger.trace("Dialog end from IM-SCF");
            outgoingDialog = null;
        }

        if (outgoingDialog == null && incomingDialog == null) {
            brokerContext.getUsageParameters().incrementRunningAuxSessionsCount(-1);
            brokerContext.getSessionManager().removeSession(sessionId);
        }
    }


    /**
     * Sends provisional 100 response, it must be called when server transaction is already there
     *
     * @param request
     */
    private void sendProvisionalResponse(Request request, ServerTransaction serverTransaction) {
        try {
            // send 100 Trying
            Response response = createNewResponse(Response.TRYING, request);
            serverTransaction.sendResponse(response);
            logger.debug("Send provisional response:\n{}", response);

        } catch (InvalidArgumentException | SipException e) {
            // not possible to send provisional response, no panic yet
            logger.warn("Unable to send provisional response", e);
        }
    }

    private boolean checkRetransmission(Request previousReq, Request newReq) {
        if (previousReq != null) {
            ViaHeader previousVia = (ViaHeader) previousReq.getHeader(ViaHeader.NAME);
            ViaHeader incomingVia = (ViaHeader) newReq.getHeader(ViaHeader.NAME);
            if (previousVia.getBranch().equals(incomingVia.getBranch())) {
                return true;
            }
        }
        return false;
    }
}

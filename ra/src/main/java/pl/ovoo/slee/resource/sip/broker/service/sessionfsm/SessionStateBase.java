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
package pl.ovoo.slee.resource.sip.broker.service.sessionfsm;

import gov.nist.javax.sip.DialogTimeoutEvent;
import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.dispatcher.InternalServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.ASHandler;
import pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler;
import pl.ovoo.slee.resource.sip.broker.service.HandlerReferenceWrapper;
import pl.ovoo.slee.resource.sip.broker.service.OrchestratedSession;
import pl.ovoo.slee.resource.sip.broker.service.SendResponseError;
import pl.ovoo.slee.resource.sip.broker.service.UnexpectedSipMessageError;
import pl.ovoo.slee.resource.sip.broker.service.UnrecoverableError;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionUnavailableException;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ExtensionHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.EventObject;
import java.util.Iterator;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.ANSWERED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.INVITED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.PROVISIONAL;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SESSION_PROGRESS_CONFIRMED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SESSION_PROGRESS_REPORTED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATING;

/**
 * This is the base state for all the session states.
 */
public abstract class SessionStateBase implements State {

    protected static final String TAG_100_REL = "100rel";
    protected static final String X_FCI = "x-fci";
    protected static final String X_AS = "x-as";
    protected final OrchestratedSession session;
    protected final SessionContext context;
    protected final Logger logger;

    public SessionStateBase(OrchestratedSession session) {
        this.session = session;
        this.context = session.getSessionContext();
        logger = context.getSessionLogger(getClass());
    }

    @Override
    public State handleEvent(EventObject event) {

        if (event instanceof RequestEvent) {

            try {
                return handleRequest((RequestEvent) event);
            } catch (SendResponseError | UnrecoverableError e) {
                return handleInternalError("Request processing error - " + e.getMessage(), e);
            }

        } else if (event instanceof ResponseEvent) {

            try {
                return handleResponse((ResponseEvent) event);
            } catch (Exception e){
                return handleInternalError("Response processing error - " + e.getMessage(), e);
            }

        } else if (event instanceof TimeoutEvent) {

            try {
                return processTimeout((TimeoutEvent) event);
            } catch (SendResponseError | UnexpectedSipMessageError e) {
                return handleInternalError("Timeout processing error - " + e.getMessage(), e);
            }

        } else if (event instanceof DialogTimeoutEvent) {
            logger.debug("Received DialogTimeoutEvent in state: {}", getClass().getSimpleName());
            checkAndRemoveSession(((DialogTimeoutEvent) event).getDialog());
            return this;

        } else if (event instanceof DialogTerminatedEvent) {
            logger.debug("Received DialogTerminatedEvent in state: {}", getClass().getSimpleName());
            checkAndRemoveSession(((DialogTerminatedEvent) event).getDialog());
            return this;
        }

        throw new UnsupportedOperationException("Unsupported event: " + getClass().getName());
    }

    /**
     * Decreases number of pending dialogs.
     * If this was the last dialog, the session is removed from the map.
     *
     * @return true if this was the last dialog and the session was unmapped
     */
    protected boolean checkAndRemoveSession(Dialog dialog) {
        logger.trace("checkAndRemoveSession, dialog to remove: {}", dialog.getCallId());
        if(context.removeDialog(dialog)){
            // check this count only if dialog was removed
            if(context.getDialogsCount() == 0){
                logger.trace("Last dialog terminated, removing the session for key: {}",
                        context.itsSession.info.getSessionId());
                context.getSessionManager().removeSession(context.itsSession.info.getSessionId());
                context.brokerContext.getUsageParameters().incrementRunningOrchestratedSessionsCount(-1);
                return true;
            } else {
                // still some dialog to complete
                logger.trace("Still {} dialogs pending", context.getDialogsCount());
                return false;
            }
        } else {
            // false from removeDialog means there was no dialog in the map
            logger.trace("Dialog already removed, still {} dialogs pending", context.getDialogsCount());
            return false;
        }
    }


    protected State handleRequest(RequestEvent event)
            throws SendResponseError, UnrecoverableError {
        logger.trace("handleRequest");
        Request request = event.getRequest();

        if(request.getMethod().equals(Request.INVITE)){

            // do common peprocessing for all the INVITE requests, check if retransmission not happened
            if(preprocessInviteRequest(event)){
                return handleInvite(event);
            } else {
                // it was a retransmission of the INVITE, nothing to do
                return this;
            }

        } else if (request.getMethod().equals(Request.ACK)){

            return handleAck(event);

        } else if (request.getMethod().equals(Request.BYE)){

            return handleBye(event);

        } else if (request.getMethod().equals(Request.PRACK)){

            return handlePrack(event);

        } else if (request.getMethod().equals(Request.CANCEL)){

            sendOkToCancel(request,event.getServerTransaction());

            return handleCancel(event);

        } else if (request.getMethod().equals(Request.OPTIONS)){

            return handleOptions(event);

        } else if (request.getMethod().equals(Request.INFO)){

            return handleInfo(event);

        } else {
            // TODO: add support for other methods
            logger.warn("Unsupported method: {}", request.getMethod());
            sendImmediateErrorResponse(Response.METHOD_NOT_ALLOWED, event, "Method not supported");

            return this;
        }
    }


    /*
     * This does the following preprocessing of the request:
     * - crates ServerTransaction and stores it in the handler
     * - stores incoming Dialog in the handler (if not exists)
     * - stores received Request in the handler
     * - in case of INVITE: sends 100 Trying provisional response
     * - in case of INVITE: stores the request
     *
     * @param requestEvent
     *
     * @returns true if the event is ready to be processed, false if this is a retransmission
     *
     * @throws TransactionAlreadyExistsException
     * @throws TransactionUnavailableException
     */
    private boolean preprocessInviteRequest(RequestEvent requestEvent) throws UnrecoverableError {
        Request incomingRequest = requestEvent.getRequest();
        logger.trace("preprocessInviteRequest:\n{}",requestEvent.getRequest());

        try {
            ServerTransaction serverTransaction;
            // check if there is no ImScf Incoming Dialog yet
            if (!context.imScfHandlerA.isIncomingDialogEstablished()) {
                logger.trace("Initial INVITE from ImScf, store Incoming Dialog and transaction");

                if(context.imScfHandlerA.getLastIncomingRequest() != null &&
                        context.imScfHandlerA.getLastIncomingRequest().getHeader(CallIdHeader.NAME).equals(
                                                                incomingRequest.getHeader(CallIdHeader.NAME))){
                    logger.warn("INVITE retransmission for already processing dialog");
                    return false;
                }

                serverTransaction = context.imScfHandlerA.getNewServerTransaction(incomingRequest);

                context.imScfHandlerA.setLastIncomingRequest(incomingRequest);
                context.imScfHandlerA.setIncomingDialog(serverTransaction.getDialog());
                context.imScfHandlerA.setLastServerTransaction(serverTransaction);

                // associate session/handler references
                serverTransaction.getDialog().setApplicationData(context.imScfHandlerA.getReferenceWrapper());

                context.brokerContext.getUsageParameters().incrementOrchestratedSessionsCount(1);
                context.brokerContext.getUsageParameters().incrementRunningOrchestratedSessionsCount(1);

            } else {

                if ( checkInviteAlreadyProcessed(incomingRequest) ){
                    logger.warn("INVITE retransmission for already processing dialog");
                    return false;
                }

                B2BDialogsHandler invitingHandler = context.getCurrentHandler();
                logger.trace("INVITE from {}, assign dialog/transaction data to the current handler", invitingHandler);

                serverTransaction = invitingHandler.getNewServerTransaction(incomingRequest);

                invitingHandler.setLastIncomingRequest(incomingRequest);
                invitingHandler.setIncomingDialog(serverTransaction.getDialog());
                invitingHandler.setLastServerTransaction(serverTransaction);

                // associate session/handler references
                serverTransaction.getDialog().setApplicationData(invitingHandler.getReferenceWrapper());
            }

            context.addDialog(serverTransaction.getDialog());

            context.setLastIncomingInvite(incomingRequest);
            sendTryingResponse(incomingRequest, serverTransaction);

        } catch (TransactionAlreadyExistsException | TransactionUnavailableException e){
            logger.warn("Error while processing incoming INVITE", e);
            throw new UnrecoverableError(e);
        }

        return true;

    }

    protected State handleInvite(RequestEvent event) throws SendResponseError {
        throw new UnsupportedOperationException("INVITE request not expected in this state: " + getClass()
                .getName());
    }

    /**
     * This process the postponed CANCEL request (from previous handler).
     * If response is not final it's time to send CANCEL.
     * Otherwise (final response) BYE is sent towards the next handler.
     *
     * @param statusCode        - response code
     * @param respondingHandler - responding handler
     *
     * @return next state
     */
    protected State postponedCancel(int statusCode, B2BDialogsHandler respondingHandler)
            throws UnrecoverableError, SendResponseError {
        logger.debug("postponedCancel");

        B2BDialogsHandler previousHandler = respondingHandler.getPreviousHandler();

        if(statusCode < Response.OK){
            // next handler state is either PROVISIONAL, SESSION_PROGRESS_REPORTED or SESSION_PROGRESS_CONFIRMED
            logger.debug("Send CANCEL to handler: {}", respondingHandler);
            respondingHandler.sendCancel();

        } else if (statusCode >= Response.OK && statusCode < Response.BAD_EXTENSION ){
            logger.debug("Next handler : {} ANSWERED. Sending BYE rather than CANCEL", respondingHandler);
            respondingHandler.sendBye();
            respondingHandler.setHandlerState(TERMINATING);

            logger.debug("Sending REQUEST_TERMINATED back, ceased interaction with chain in forward direction");
            // reset current handler to the one that would get the response
            context.setCurrentHandler(previousHandler);
            checkAndApplyRollback(previousHandler);
            return sendNewResponseBack(previousHandler, Response.REQUEST_TERMINATED);

        } else {
            // error response received while canceled incoming leg
            logger.debug("Sending REQUEST_TERMINATED back, ceased interaction with chain in forward direction");
            // reset current handler to the one that would get the response
            context.setCurrentHandler(previousHandler);
            checkAndApplyRollback(previousHandler);
            respondingHandler.setHandlerState(TERMINATED);
            return sendNewResponseBack(previousHandler, Response.REQUEST_TERMINATED);

        }

        return this;
    }

    protected State handleAck(RequestEvent event) throws UnrecoverableError {
        throw new UnsupportedOperationException("ACK request not expected in this state: " + getClass()
                .getName());
    }

    protected State handlePrack(RequestEvent event) throws UnrecoverableError {
        throw new UnsupportedOperationException("PRACK request not expected in this state: " + getClass()
                .getName());
    }

    protected State handleBye(RequestEvent event) throws UnrecoverableError {
        throw new UnsupportedOperationException("BYE request not expected in this state: " + getClass()
                .getName());
    }

    protected State handleCancel(RequestEvent event) throws SendResponseError, UnrecoverableError {
        throw new UnsupportedOperationException("CANCEL request not expected in this state: " + getClass()
                .getName());
    }

    protected State handleOptions(RequestEvent event) {
        throw new UnsupportedOperationException("OPTIONS request not expected in this state: " + getClass()
                .getName());
    }

    protected State handleInfo(RequestEvent event) throws UnrecoverableError {
        throw new UnsupportedOperationException("INFO request not expected in this state: " + getClass()
                .getName());
    }

    protected State handleResponse(ResponseEvent responseEvent)
            throws SendResponseError, UnexpectedSipMessageError, UnrecoverableError{
        throw new UnsupportedOperationException("ResponseEvent not expected in this state: " + getClass().getName());
    }

    protected State processTimeout(TimeoutEvent timeoutEvent) throws SendResponseError, UnexpectedSipMessageError {
        throw new UnsupportedOperationException("TimeoutEvent not expected in this state: " + getClass().getName());
    }

    protected State sendRequestBack(B2BDialogsHandler handlerToForwardRequest, Request request, B2BDialogsHandler
            specialInfoSender) throws UnrecoverableError {
        logger.debug("sendRequestBack {}", handlerToForwardRequest);

        try {
            Dialog dialog = handlerToForwardRequest.getIncomingDialog();
            Request newRequest = MessageUtils.createOnDialogRequest(request, dialog, context.brokerContext
                    .getBrokerContactHeader(), logger);
            handlerToForwardRequest.sendRequestOnDialog(newRequest, dialog, specialInfoSender);

        } catch (SipException | ParseException e) {
            logger.error("Unable to send request back", e);
            throw new UnrecoverableError(e);
        }

        return this;
    }

    protected State sendRequestForward(B2BDialogsHandler handlerToForwardRequest, Request request, B2BDialogsHandler
            specialInfoSender) throws UnrecoverableError {
        logger.debug("sendRequestForward {}", handlerToForwardRequest);
        try {

            Dialog dialog = handlerToForwardRequest.getOutgoingDialog();
            Request newRequest = MessageUtils.createOnDialogRequest(request, dialog, context.brokerContext
                    .getBrokerContactHeader(), logger);
            handlerToForwardRequest.sendRequestOnDialog(newRequest, dialog, specialInfoSender);

        } catch (SipException | ParseException e) {
            logger.error("Unable to send request forward", e);
            throw new UnrecoverableError(e);
        }
        return this;
    }

    protected State handleCommonInfoLogic(RequestEvent event) throws UnrecoverableError {
        Request info = event.getRequest();
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) event.getDialog().getApplicationData();
        B2BDialogsHandler infoSender = wrapper.getDialogHandler();

        ServerTransaction serverTransaction = event.getServerTransaction();
        infoSender.setLastInfoServerTransaction(serverTransaction);
        infoSender.setLastIncomingInfoRequest(info);

        boolean infoFromOutgoingLeg = (event.getDialog() == infoSender.getOutgoingDialog());

        ExtensionHeader xfci = (ExtensionHeader) info.getHeader(X_FCI);
        if (xfci != null && "true" .equals(xfci.getValue())) {
            logger.debug("This is a special INFO - not chained");

            if(infoFromOutgoingLeg){
                logger.trace("INFO on calling party leg");
                return sendRequestBack(context.imScfHandlerA, info, infoSender);
            } else {
                logger.trace("INFO on called party leg");
                if(context.imScfHandlerB.getHandlerState() == TERMINATED){
                    logger.warn("Next INFO handler already terminated, returning error response");
                    infoSender.respondToPendingRequestsOnDialogTerminatingResponse();
                    return this;
                } else {
                    return sendRequestForward(context.imScfHandlerB, info, infoSender);
                }

            }
        }

        // chaining INFO in either direction
        if(infoFromOutgoingLeg){
            logger.trace("INFO in backward direction");
            B2BDialogsHandler handlerToForwardInfo = infoSender.getPreviousHandler();
            return sendRequestBack(handlerToForwardInfo, info);
        } else {
            logger.trace("INFO in forward direction");
            B2BDialogsHandler handlerToForwardInfo = infoSender.getNextHandler();
            if(handlerToForwardInfo.getHandlerState() == TERMINATED){
                logger.warn("Next INFO handler already terminated, returning error response");
                infoSender.respondToPendingRequestsOnDialogTerminatingResponse();
                return this;
            } else {
                return sendRequestForward(handlerToForwardInfo, info);
            }
        }
    }

    private State sendRequestBack(B2BDialogsHandler handlerToPassRequest, Request request) throws UnrecoverableError {
        return sendRequestBack(handlerToPassRequest, request, null);
    }

    private State sendRequestForward(B2BDialogsHandler handlerToPassRequest, Request request) throws
            UnrecoverableError {
        return sendRequestForward(handlerToPassRequest, request, null);
    }


    /**
     * Sends provisional 100 response, to be called when server transaction is already there
     *
     * @param request
     */
    private void sendTryingResponse(Request request, ServerTransaction serverTransaction) {
        try {
            // send 100 Trying
            Response response = context.getMessageFactory().createResponse(Response.TRYING, request);
            serverTransaction.sendResponse(response);
            logger.debug("Send provisional response:\n{}", response);

        } catch (SipException | ParseException | InvalidArgumentException e) {
            // not possible to send provisional response, no panic yet
            logger.warn("Unable to send Trying response", e);
        }
    }


    /**
     * Sends 200 OK response to CANCEL request
     *
     * @param request           - CANCEL request
     * @param serverTransaction - CANCEL server transaction
     */
    protected void sendOkToCancel(Request request, ServerTransaction serverTransaction) {
        try {
            // send 200 OK
            Response response = context.getMessageFactory().createResponse(Response.OK, request);
            serverTransaction.sendResponse(response);
            logger.debug("Send final response:\n{}", response);

        } catch (InvalidArgumentException | ParseException | SipException e) {
            // any exception while sending CANCEL response, not possible to do anything
            logger.warn("Unable to respond with 200 OK to CANCEL: {}", e.getMessage());
        }
    }

    /**
     * Sends final 487 Request Terminated response
     *
     * @param incomingRequest
     */
    protected void sendRequestTerminatedResponse(Request incomingRequest) {
        logger.trace("sendRequestTerminatedResponse");

        try {
            B2BDialogsHandler invitingHandler = context.getCurrentHandler();
            ServerTransaction serverTransaction = invitingHandler.getNewServerTransaction(incomingRequest);

            invitingHandler.setLastIncomingRequest(incomingRequest);
            invitingHandler.setIncomingDialog(serverTransaction.getDialog());
            invitingHandler.setLastServerTransaction(serverTransaction);


            // send 487 Request Terminated
            Response response = context.getMessageFactory().createResponse(Response.REQUEST_TERMINATED, incomingRequest);
            serverTransaction.sendResponse(response);
            logger.debug("Send final response:\n{}", response);

        } catch (Exception e) {
            e.printStackTrace();
            logger.warn("Unable to send Request Terminated (487) response: {} {}", e.getMessage(),e);
        }
    }


    /**
     * This forwards the error response back to previous node (AS or IM-SCF)
     *
     * @param handlerToPassResponse B2BDialogsHandler to pass the response back
     * @param response Response to forward
     *
     * @return the next state
     */
    protected State forwardErrorResponseBack(B2BDialogsHandler handlerToPassResponse, Response response) throws
            SendResponseError {
        logger.debug("forwardErrorResponseBack, next Handler to forward response: {}", handlerToPassResponse);

        handlerToPassResponse.forwardResponse(response, handlerToPassResponse.getIncomingDialog());

        // stay in ChainingState when sending error response back
        // this allow AS to decide how to continue with the call/session
        if(this instanceof ChainingState){
            return this;
        } else {
            return new ChainingState(session);
        }
    }

    protected void processInfoResponse(ResponseEvent event)
            throws SendResponseError {
        logger.trace("processInfoResponse");
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) event.getClientTransaction().getApplicationData();

        B2BDialogsHandler handlerToPassResponse;
        Dialog dialog;
        if(wrapper.getSpecialInfoSender() != null){
            handlerToPassResponse = wrapper.getSpecialInfoSender();
            // reset this indicator
            wrapper.setSpecialInfoSender(null);
            logger.debug("FCI INFO response received, Handler to forward response: {}",
                    handlerToPassResponse);
            dialog = handlerToPassResponse.getIncomingDialog();
        } else if(event.getDialog() == wrapper.getDialogHandler().getIncomingDialog()) {
            // check response direction
            handlerToPassResponse = wrapper.getDialogHandler().getNextHandler();
            logger.debug("INFO response received from previous handler, Handler to forward response: {}",
                    handlerToPassResponse);
            dialog = handlerToPassResponse.getOutgoingDialog();
        } else {
            handlerToPassResponse = wrapper.getDialogHandler().getPreviousHandler();
            logger.debug("INFO response received from next handler, Handler to forward response: {}",
                    handlerToPassResponse);
            dialog = handlerToPassResponse.getIncomingDialog();
        }

        handlerToPassResponse.forwardInfoResponse(event.getResponse(), dialog);
    }


    /**
     * Retrieves B2BDialogsHandler from transaction application data
     *
     * @param ctx   - ClientTransaction to fetch handler reference from
     *
     * @return B2BDialogsHandler corresponding to the request event
     */
    protected B2BDialogsHandler fetchB2BHandlerFromClientTx(ClientTransaction ctx){
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) ctx.getApplicationData();
        return wrapper.getDialogHandler();
    }

    /**
     * Retrieves B2BDialogsHandler from dialog application data
     *
     * @param dialog   - Dialog to fetch handler reference from
     *
     * @return B2BDialogsHandler corresponding to the request event
     */
    protected B2BDialogsHandler fetchB2BHandlerFromDialog(Dialog dialog){
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) dialog.getApplicationData();
        return wrapper.getDialogHandler();
    }

    /**
     * Sends immediate error response towards initiating party.
     * This method should be used to terminate the session in case of no outgoing dialogs created yet.
     *
     * @param statusCode - response status code
     * @param event      - request event that processing failed
     * @param reason     - additional reason message
     */
    protected void sendImmediateErrorResponse(int statusCode, RequestEvent event, String reason) {
        try {
            Response response = context.brokerContext.messageFactory.createResponse(statusCode, event.getRequest());
            if(reason != null){
                response.setReasonPhrase(reason);
            }

            if (event.getSource() instanceof InternalServiceProvider) {
                context.brokerContext.internalServiceProvider.sendResponse(response);
            } else {
                context.brokerContext.externalServiceProvider.sendResponse(response);
            }

        } catch (SipException | ParseException e) {
            // nothing to do in this state
            logger.warn("Unable to send error response", e);
        }
    }


    /**
     * Executes handleInternalError with removeSession set to false
     *
     * @param errorMessage  - error message to log
     * @param exception     - exception
     *
     * @return InternalErrorState instance
     */
    protected State handleInternalError(String errorMessage, Exception exception) {
        return handleInternalError(errorMessage, exception, false);
    }


    /**
     * This is the common entry method to execute in case of unrecoverable error or exception
     * that cannot be processed otherwise.
     * It disconnects all the outgoing dialogs, log error messages
     *
     * It proceeds to InternalErrorState in order to handle upcoming Requets/Responses
     * till end of the session (all dialogs removed)
     *
     * When removeSession parameter set to true, it also removes the session from the map.
     * Otherwise the session would be eventually removed when both IM-SCF dialogs are terminated
     *
     * @param errorMessage  - error message to log
     * @param exception     - exception
     * @param removeSession - indicates if the session should be removed
     *
     * @return InternalErrorState instance
     */
    protected State handleInternalError(String errorMessage, Exception exception, boolean removeSession) {
        logger.debug("handleInternalError, removeSession: {}", removeSession);

        logger.error("Cleanup session after error: {}", errorMessage, exception);

        // release all dialogs
        disconnectAllDialogsOnError();

        if(removeSession){
            context.getSessionManager().removeSession(session.getID());
            context.brokerContext.getUsageParameters().incrementRunningOrchestratedSessionsCount(-1);
            // no session - no more events to come
            return null;
        }

        // increment aborted sessions counter
        context.brokerContext.getUsageParameters().incrementAbortedSessionsCount(1L);

        return new InternalErrorState(session);
    }


    /**
     * Clears all outgoing dialogs.
     * It sends either BYE or CANCEL towards all INVITED services and IM-SCF.
     */
    private void disconnectAllDialogsOnError(){
        logger.debug("disconnectAllDialogsOnError");

        B2BDialogsHandler handler = context.imScfHandlerA.getNextHandler();
        do {
            switch (handler.getHandlerState()) {
                case INITIAL:
                    logger.trace("Handler {} not invited, no need to clear", handler);
                    break;
                case INVITED:
                    logger.trace("Mark as SET TO CANCEL", handler);
                    break;
                case PROVISIONAL:
                    logger.trace("Disconnecting {} with CANCEL", handler);
                    handler.sendTerminatingCancel();
                    break;
                case SESSION_PROGRESS_REPORTED:
                case SESSION_PROGRESS_CONFIRMED:
                case ANSWERED:
                    logger.trace("Disconnecting {} with BYE", handler);
                    handler.sendTerminatingBye();
            }

            // get next handler to clear
            handler = handler.getNextHandler();

        } while (!handler.isImScf());

        // for IM-SCF send BYE to B leg if required, incoming leg from IM-SCF will timeout eventually
        if(context.imScfHandlerB.getHandlerState() == SESSION_PROGRESS_REPORTED ||
                context.imScfHandlerB.getHandlerState() == SESSION_PROGRESS_CONFIRMED ||
                context.imScfHandlerB.getHandlerState() == ANSWERED) {
            // send BYE only in case of Early or Confirmed dialog state
            logger.trace("Disconnecting {} with BYE", handler);
            context.imScfHandlerB.sendTerminatingBye();
        } else if(context.imScfHandlerB.getHandlerState() == PROVISIONAL){
            logger.trace("Disconnecting {} with CANCEL", handler);
            handler.sendTerminatingCancel();
        } else if(context.imScfHandlerB.getHandlerState() == INVITED){
            logger.trace("Mark as SET TO CANCEL", handler);
        }

        logger.debug("All dialogs disconnected or marked to disconnect");
    }

    /**
     * This performs the rollback of the AS handlers in the chain
     * Applicable only to AS Handlers, i.e. no sense to rollback if the next handler
     * to response is IM-SCF
     *
     * @param handlerToRespond - the next handler in the chain to pass the response
     */
    protected void checkAndApplyRollback(B2BDialogsHandler handlerToRespond){
        if(!handlerToRespond.isImScf()){
            // previous AS might execute a different logic at reception of this error
            context.rollbackAsHandlers((ASHandler) handlerToRespond);
        }
    }

    /**
     * Creates default error response with provided status code for given request
     *
     * @param statusCode status code of the response
     * @param request request to create response for
     *
     * @return new Response object
     */
    private Response createErrorResponse(int statusCode, Request request){
        try {
            return context.getMessageFactory().createResponse(statusCode, request);
        } catch (ParseException ignored) {
            // will not happen if Response constants are used
            throw new IllegalArgumentException("Unexpected response code: " + statusCode);
        }
    }

    /**
     * Sends a new Response back to previous handler
     *
     * @param handlerToRespond   - handler to send response back
     * @param statusCode         - status code of the response
     *
     * @return next state to continue processing
     *
     * @throws SendResponseError in case of any exception when parsing or sending response
     */
    protected State sendNewResponseBack(B2BDialogsHandler handlerToRespond, int statusCode) throws SendResponseError {
        logger.debug("Sending response towards previous handler: {}", handlerToRespond);
        if(handlerToRespond.getLastIncomingRequest() != null){
            Response response = createErrorResponse(statusCode, handlerToRespond.getLastIncomingRequest());
            handlerToRespond.forwardResponse(response, handlerToRespond.getIncomingDialog());
        } else {
            logger.debug("Last incoming request null, final response already sent back");
        }

        // stay in ChainingState when sending error response back
        // this allow AS to decide how to continue with the call/session
        if(this instanceof ChainingState){
            return this;
        } else {
            return new ChainingState(session);
        }
    }

    /**
     * Checks if this incoming INVITE has been already processed and its CallId/last Via header pair stored
     * @param invite - incoming INVITE request to check
     * @return true if the INVITE is a retransmission
     */
    @SuppressWarnings("unchecked")
    private boolean checkInviteAlreadyProcessed(Request invite){
        ViaHeader storedVia = context.findInitialInviteLastVia((CallIdHeader) invite.getHeader(CallIdHeader.NAME));
        if (storedVia != null) {
            ViaHeader viaHeader = null;
            Iterator<ViaHeader> viaHeaders = invite.getHeaders(ViaHeader.NAME);
            while (viaHeaders.hasNext()) {
                viaHeader = viaHeaders.next();
            }

            if (storedVia.equals(viaHeader)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates new response with provided status code for given request
     *
     * @param statusCode status code of the response
     * @param request    request to create response for
     * @return new Response object
     */
    protected Response createNewResponse(int statusCode, Request request) {
        try {
            return context.getMessageFactory().createResponse(statusCode, request);
        } catch (ParseException ignored) {
            // will not happen if Response constants are used
            throw new IllegalArgumentException("Unexpected response code: " + statusCode);
        }
    }
}

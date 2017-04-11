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

import pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler;
import pl.ovoo.slee.resource.sip.broker.service.HandlerReferenceWrapper;
import pl.ovoo.slee.resource.sip.broker.service.OrchestratedSession;
import pl.ovoo.slee.resource.sip.broker.service.SendResponseError;
import pl.ovoo.slee.resource.sip.broker.service.UnrecoverableError;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.Dialog;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.TimeoutEvent;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExtensionHeader;
import javax.sip.header.ReasonHeader;
import javax.sip.header.RequireHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.ACKED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.ANSWERED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.INVITED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.PROVISIONAL;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SET_TO_CANCEL;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATING;

/**
 * Session enters this state after sending B leg INVITE towards IM-SCF
 * It forwards the responses and ACK requests
 *
 */
public class WaitingForImScfState extends SessionStateBase {

    private boolean isAckReceivedFromImsCf = false;
    private boolean isAckSentToImsCf = false;

    public WaitingForImScfState(OrchestratedSession session) {
        super(session);
    }


    /**
     * This method process the CANCEL Request
     * It sends CANCEL to the next handler in the chain, waits for provisional response to send CANCEL
     * or forwards the response back (in case next AS already canceled).
     * The session stays in this state in order to pass 487 response back (rollback)
     * and handle potential new INVITE from AS (initiating CANCEL).
     *
     * @param event - request even (CANCEL)
     */
    @Override
    protected State handleCancel(RequestEvent event) throws SendResponseError, UnrecoverableError {
        logger.trace("handleCancel");

        B2BDialogsHandler cancelSender = fetchB2BHandlerFromDialog(event.getDialog());
        B2BDialogsHandler nextHandler = cancelSender.getNextHandler();

        if(nextHandler.getHandlerState() == INVITED) {
            logger.debug("Next handler: {} waiting for any response before sending CANCEL");
            nextHandler.setHandlerState(SET_TO_CANCEL);

        } else if (nextHandler.getHandlerState() == TERMINATED) {
            logger.debug("Next handler: {} already TERMINATED, send REQUEST_TERMINATED back and rollback", nextHandler);

            // reset current handler to the one that would get the response
            context.setCurrentHandler(cancelSender);
            checkAndApplyRollback(cancelSender);
            return sendNewResponseBack(cancelSender, Response.REQUEST_TERMINATED);

        } else if (nextHandler.getHandlerState() == ANSWERED || nextHandler.getHandlerState() == ACKED) {
            // race condition, next handler ANSWERED -> 200 OK already passed back to previous handler
            logger.debug("Next handler : {} ANSWERED/ACKED. Sending 481 response to CANCEL", nextHandler);
            return sendNewResponseBack(cancelSender, Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST);

        } else {
            // next handler state is either PROVISIONAL, SESSION_PROGRESS_REPORTED or SESSION_PROGRESS_CONFIRMED
            logger.debug("Forward CANCEL to next handler: {} in state: {}",nextHandler, nextHandler.getHandlerState());
            nextHandler.sendCancel();
        }

        return this;
    }


    /**
     * Process BYE request.
     * In this state bye happens in case BYE triggered by previous CANCEL.
     * Therefore BYE needs to be forwarded to the next handler.
     *
     * @param event - BYE request event
     *
     * @return next state to continue in
     */
    protected State handleBye(RequestEvent event)
            throws UnrecoverableError {
        logger.trace("handleBye");

        Request incomingBye = event.getRequest();
        logger.debug("Incoming BYE:\n{}", incomingBye);

        B2BDialogsHandler byeSender = fetchB2BHandlerFromDialog(event.getDialog());

        ServerTransaction serverTransaction = event.getServerTransaction();
        byeSender.setLastServerTransaction(serverTransaction);
        byeSender.setLastIncomingRequest(incomingBye);

        B2BDialogsHandler nextByeHandler = byeSender.getNextHandler();
        logger.debug("Found next Handler in the chain to handle BYE: {}", nextByeHandler);

        nextByeHandler.forwardBye(incomingBye, nextByeHandler.getOutgoingDialog());
        nextByeHandler.setHandlerState(TERMINATING);

        return this;
    }


    @Override
    protected State handleAck(RequestEvent requestEvent) throws UnrecoverableError {
        return forwardAckToNextAs(requestEvent);
    }

    /**
     * This method process the sip Response from IM-SCF (in this state)
     *
     * @param event - Request event
     */
    @Override
    protected State handleResponse(ResponseEvent event) throws SendResponseError, UnrecoverableError {
        logger.debug("handleResponse, statusCode: {}", event.getResponse().getStatusCode());
        Response response = event.getResponse();
        int statusCode = response.getStatusCode();
        B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(event.getClientTransaction());

        CSeqHeader cSeq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        if(cSeq.getMethod().equals(Request.CANCEL)){
            logger.debug("CANCEL response received, no action");
            return this;
        }

        if(respondingHandler.getHandlerState() == SET_TO_CANCEL){
            return postponedCancel(statusCode, respondingHandler);

        } else if (respondingHandler.getHandlerState() == TERMINATED) {
            logger.debug("Response from TERMINATED handler: {}, ignoring", respondingHandler);
            return this;
        }

        if( statusCode == Response.OK){

            return processResponseOK(event);

        } else if (response.getHeader(ReasonHeader.NAME) != null || // Reason header present
                statusCode >= Response.MULTIPLE_CHOICES) { // any error response
            logger.debug("Error response from IM-SCF, remove outgoing dialog and forward response back");
            context.imScfHandlerB.setOutgoingDialog(null);

            // reset current handler to the one that would get the response
            context.setCurrentHandler(respondingHandler.getPreviousHandler());
            checkAndApplyRollback(respondingHandler.getPreviousHandler());
            return forwardErrorResponseBack(respondingHandler.getPreviousHandler(), event.getResponse());

        } else if (statusCode == Response.SESSION_PROGRESS) {

            return processResponseSessionProgress(event);

        } else if (statusCode == Response.TRYING) {
            logger.debug("Received provisional response: {}, no action", statusCode);
            respondingHandler.setHandlerState(PROVISIONAL);

        } else if (statusCode > Response.TRYING && statusCode < Response.OK){
            logger.debug("Received provisional response: {},forwarding back", statusCode);
            B2BDialogsHandler handlerToResponse = respondingHandler.getPreviousHandler();
            handlerToResponse.forwardResponse(response, handlerToResponse.getIncomingDialog());
            respondingHandler.setHandlerState(PROVISIONAL);
        }

        return this;
    }

    /**
     * This method process the sip INFO Request
     *
     * @param event - Request event
     */
    @Override
    protected State handleInfo(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleInfo");
        return handleCommonInfoLogic(event);
    }

    private State processResponseOK(ResponseEvent responseEvent) throws SendResponseError {
        CSeqHeader cseq = (CSeqHeader) responseEvent.getResponse().getHeader(CSeqHeader.NAME);
        logger.trace("processResponseOK: {}", cseq.getMethod());

        // find session handler from client transaction
        B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(responseEvent.getClientTransaction());

        if(cseq.getMethod().equals(Request.INVITE)) {
            respondingHandler.setHandlerState(ANSWERED);
            B2BDialogsHandler handlerToPassResponse = respondingHandler.getPreviousHandler();
            logger.debug("Successful response received, next Handler to forward response: {}", handlerToPassResponse);

            handlerToPassResponse.forwardResponse(responseEvent.getResponse(),
                    handlerToPassResponse.getIncomingDialog());

        } else if(cseq.getMethod().equals(Request.CANCEL)) {

            logger.debug("CANCEL response received, previous handler already answered with 200 OK, no action");

        } else if (cseq.getMethod().equals(Request.PRACK)) {

            B2BDialogsHandler handlerToPassResponse = respondingHandler.getPreviousHandler();
            logger.debug("Regular PRACK response, Handler to forward response: {}",  handlerToPassResponse);
            handlerToPassResponse.forwardPrackResponse(responseEvent.getResponse(),
                                                                            handlerToPassResponse.getIncomingDialog());

            if(handlerToPassResponse.isImScf()){
                context.setReliableResponseProcessing(false);
            }

            return this;

        } else if (cseq.getMethod().equals(Request.INFO)) {

            processInfoResponse(responseEvent);

        } else if(cseq.getMethod().equals(Request.BYE)) {
            logger.debug("BYE response received");

            if(responseEvent.getDialog() == respondingHandler.getOutgoingDialog()){
                respondingHandler.setHandlerState(TERMINATED);
                B2BDialogsHandler handlerToPassResponse = respondingHandler.getPreviousHandler();

                if(handlerToPassResponse.getHandlerState() == TERMINATING){
                    logger.debug("Previous handler: {} TERMINATING state, forwarding BYE response", handlerToPassResponse);

                    handlerToPassResponse.forwardResponse(responseEvent.getResponse(),
                            handlerToPassResponse.getIncomingDialog());
                }

            } else {
                logger.debug("Response to BYE from Callee, sending in forward direction in the chain");
                B2BDialogsHandler handlerToPassResponse = respondingHandler.getNextHandler();

                handlerToPassResponse.forwardResponse(responseEvent.getResponse(),
                        handlerToPassResponse.getOutgoingDialog());

            }

        } else {
            logger.warn("Unexpected Response in WaitingForImScfState:{}", cseq.getMethod());
        }

        return this;
    }


    private State forwardAckToNextAs(RequestEvent requestEvent) throws UnrecoverableError {
        logger.debug("ACK to INVITE OK, create and send ACK to the next Handler");
        try {
            Dialog dialog = requestEvent.getDialog();
            Request receivedAck = requestEvent.getRequest();

            // find associated handler
            HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) dialog.getApplicationData();
            B2BDialogsHandler currentAckHandler = wrapper.getDialogHandler();

            B2BDialogsHandler nextAckHandler = currentAckHandler.getNextHandler();
            logger.debug("Found next Handler to forward ACK: {}", nextAckHandler);

            long cseqNumber = ((CSeqHeader) nextAckHandler.getLastOutgoingInvite().
                    getHeader(CSeqHeader.NAME)).getSeqNumber();

            Request ack = MessageUtils.createAck(receivedAck, cseqNumber, nextAckHandler.getOutgoingDialog(), context.brokerContext.getBrokerContactHeader(), logger);

            nextAckHandler.getOutgoingDialog().sendAck(ack);
            nextAckHandler.setHandlerState(ACKED);

            if (currentAckHandler.isImScf()) {
                isAckReceivedFromImsCf = true;
                logger.trace("set isAckReceivedFromImsCf");
            }
            if (nextAckHandler.isImScf()) {
                isAckSentToImsCf = true;
                logger.trace("set isAckSentToImsCf");
            }

            if(isAckReceivedFromImsCf && isAckSentToImsCf ) {
                return new SessionOngoingState(session);
            } else {
                // continue handling with this state
                return this;
            }

        } catch (ParseException | InvalidArgumentException | SipException e){
            logger.warn("Error when forwarding ACK", e);
            throw new UnrecoverableError(e);
        }
    }


    private State processResponseSessionProgress(ResponseEvent responseEvent) throws SendResponseError {
        // find session handler from client transaction
        B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(responseEvent.getClientTransaction());
        B2BDialogsHandler previousHandler = respondingHandler.getPreviousHandler();
        logger.trace("processResponseSessionProgress from: {}", respondingHandler);
        respondingHandler.setHandlerState(PROVISIONAL);

        Response response = responseEvent.getResponse();
        RequireHeader requireHeader = (RequireHeader) response.getHeader(RequireHeader.NAME);

        ExtensionHeader xAsHeader = (ExtensionHeader) response.getHeader(X_AS);
        if(xAsHeader == null || "false".equals(xAsHeader.getValue()) ){

            // this is a regular 183 response, simply send it back
            logger.debug("Regular 183 response, forward to handler: {}", respondingHandler.getPreviousHandler());

            if(requireHeader != null && requireHeader.getOptionTag().equalsIgnoreCase(TAG_100_REL)) {
                logger.debug("Found 100rel in response, this is a reliable response.");
                previousHandler.forwardReliableProvisionalResponse(response);
                context.setReliableResponseProcessing(true);
                respondingHandler.setLastSessionProgressResponse(response);

            } else {
                logger.debug("Basic provisional response: {}, forwarding back", response.getStatusCode());
                previousHandler.forwardResponse(response, previousHandler.getIncomingDialog());
            }
        } else {
            throw new SendResponseError("Invalid response from IM-SCF - contains x-as:true header");
        }

        return this;
    }



    /**
     * This method process the sip PRACK Request
     *
     * @param event request event
     */
    @Override
    protected State handlePrack(RequestEvent event) throws UnrecoverableError {
        logger.trace("handlePrack");

        Request incomingPrack = event.getRequest();
        logger.debug("Incoming PRACK:\n{}", incomingPrack);

        // find associated handler
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) event.getDialog().getApplicationData();
        B2BDialogsHandler prackSender = wrapper.getDialogHandler();

        // unset reliable response flag to allow incoming reliable responses on this dialog
        prackSender.setPendingReliableResponse(false);

        // keep PRACK server transaction and request for current Handler
        ServerTransaction serverTransaction = event.getServerTransaction();
        prackSender.setLastPrackServerTransaction(serverTransaction);
        prackSender.setLastIncomingPrackRequest(incomingPrack);

        B2BDialogsHandler nextHandler = prackSender.getNextHandler();
        if(nextHandler.getHandlerState() == TERMINATED){
            logger.warn("Next PRACK handler already terminated, returning error response");
            prackSender.respondToPendingRequestsOnDialogTerminatingResponse();
        } else {
            logger.debug("Forwarding PRACK towards {}", nextHandler);
            nextHandler.processOutgoingPrack();
        }

        return this;
    }


    @Override
    protected State processTimeout(TimeoutEvent timeoutEvent) throws SendResponseError {

        if (timeoutEvent.isServerTransaction()) {
            // server transaction timeouts are unexpected, there is no logic predefined for this
            ServerTransaction st = timeoutEvent.getServerTransaction();
            logger.error("ServerTransaction timeout for request {}", st.getRequest().getMethod());
            throw new SendResponseError("Unexpected ServerTransaction timeout for " + st.getRequest());
        } else {
            // client transaction timeout, in this state this is considered as 408 (Request Timeout)
            if(timeoutEvent.getClientTransaction().getRequest().getMethod().equals(Request.INVITE)){
                B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(timeoutEvent.getClientTransaction());

                Dialog timeoutDialog = timeoutEvent.getClientTransaction().getDialog();
                logger.info("INVITE timeout, callID: {}", timeoutDialog.getCallId());
                // client timeout for INVITE means no future DialogTerminated event will come for this dialog
                // remove it from the map
                if(checkAndRemoveSession(timeoutDialog)){
                    logger.debug("This was the last dialog, session removed");
                    return this;
                }

                // reset current handler to the one that would get the response
                context.setCurrentHandler(respondingHandler.getPreviousHandler());
                checkAndApplyRollback(respondingHandler.getPreviousHandler());
                return sendNewResponseBack(respondingHandler.getPreviousHandler(), Response.REQUEST_TIMEOUT);
            }
        }

        return this;
    }
}

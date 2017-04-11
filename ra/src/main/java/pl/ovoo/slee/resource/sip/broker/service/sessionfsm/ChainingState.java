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
import pl.ovoo.slee.resource.sip.broker.service.UnexpectedSipMessageError;
import pl.ovoo.slee.resource.sip.broker.service.UnrecoverableError;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestrationRuleset;

import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.TimeoutEvent;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExtensionHeader;
import javax.sip.header.ReasonHeader;
import javax.sip.header.RequireHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.ACKED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.ANSWERED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.INVITED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.PROVISIONAL;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SESSION_PROGRESS_CONFIRMED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SESSION_PROGRESS_REPORTED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SET_TO_CANCEL;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATING;

/**
 * This state is responsible for handling INVITE towards all ASs and sending final INVITE towards IM-SCF
 */
public class ChainingState extends SessionStateBase {

    public ChainingState(OrchestratedSession session) {
        super(session);
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

        B2BDialogsHandler nextPrackHandler = prackSender.getNextHandler();
        if(nextPrackHandler.getHandlerState() == TERMINATED){
            logger.warn("Next PRACK handler already terminated, returning error response");
            prackSender.respondToPendingRequestsOnDialogTerminatingResponse();
        } else {
            logger.debug("Forwarding PRACK towards {}", nextPrackHandler);
            nextPrackHandler.processOutgoingPrack();
        }

        return this;
    }


    /**
     * Process the sip INVITE Request
     *
     * @param event - request even (INVITE)
     */
    @Override
    protected State handleInvite(RequestEvent event) throws SendResponseError {
        // read next handler to INVITE (from the context)
        B2BDialogsHandler nextHandler = context.getCurrentHandler().getNextHandler();
        return continueSetupWithNextAsOrImScf(event.getRequest(), nextHandler);
    }


    /**
     * Process the CANCEL Request
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
            logger.debug("Next handler : {} ANSWERED. Sending 481 response to CANCEL", nextHandler);
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
    @Override
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


    /**
     * In this state ACK would come after sending error response to previous AS,
     * therefore this event is ignored here.
     *
     * @param event - ACK event
     */
    @Override
    protected State handleAck(RequestEvent event){
        logger.trace("handleAck");
        return this;
    }


    /**
     * This method process the sip INFO Request
     *
     * @param event - request event
     */
    @Override
    protected State handleInfo(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleInfo");

        return handleCommonInfoLogic(event);
    }


    /**
     * This method process the sip Response
     *
     * @param event - response event
     */
    @Override
    protected State handleResponse(ResponseEvent event) throws SendResponseError, UnrecoverableError {
        logger.debug("handleResponse, statusCode: {}", event.getResponse().getStatusCode());
        Response response = event.getResponse();
        int statusCode = response.getStatusCode();
        B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(event.getClientTransaction());
        B2BDialogsHandler previousHandler = respondingHandler.getPreviousHandler();

        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        if(cseq.getMethod().equals(Request.CANCEL)){
            logger.debug("CANCEL response received, no action");
            // race condition happened, final response already sent by remote UA
            // the response should have been already passed to previous handler
            return this;
        }

        if(respondingHandler.getHandlerState() == SET_TO_CANCEL){
            return postponedCancel(statusCode, respondingHandler);

        } else if (respondingHandler.getHandlerState() == TERMINATED) {
            logger.debug("Response from TERMINATED handler: {}, ignoring", respondingHandler);
            return this;
        }

        if (statusCode == Response.OK) {
            return processResponseOK(event);

        } else if (statusCode == Response.SESSION_PROGRESS) {
            respondingHandler.setHandlerState(PROVISIONAL);
            return processResponseSessionProgress(event, respondingHandler, previousHandler);

        } else if (response.getHeader(ReasonHeader.NAME) != null ) { // Reason header present
            respondingHandler.setHandlerState(TERMINATED);
            // unconditional stop logic
            // reset current handler to the one that would get the response
            context.setCurrentHandler(previousHandler);
            checkAndApplyRollback(previousHandler);
            return forwardErrorResponseBack(previousHandler, event.getResponse());

        } else if (statusCode >= Response.MULTIPLE_CHOICES) {
            respondingHandler.setHandlerState(TERMINATED);
            // check if stop or skip logic for this ruleset
            OrchestrationRuleset.ErrorLogic logic = context.itsRuleset.getResponseHandling(statusCode);
            logger.debug("Error response, applying logic: {}", logic);
            if(logic == OrchestrationRuleset.ErrorLogic.STOP){
                // reset current handler to the one that would get the response
                context.setCurrentHandler(previousHandler);
                checkAndApplyRollback(previousHandler);
                return forwardErrorResponseBack(previousHandler, event.getResponse());

            } else if(skipNotPossible(respondingHandler)) {
                return forwardErrorResponseBack(previousHandler, event.getResponse());

            } else {
                // SKIP logic
                return executeSkipLogic(respondingHandler);
            }

        } else if (statusCode > Response.TRYING && statusCode < Response.OK) {
            respondingHandler.setHandlerState(PROVISIONAL);
            logger.debug("Provisional response: {}, forwarding back", response.getStatusCode());
            previousHandler.forwardResponse(response, previousHandler.getIncomingDialog());

        } else {
            // only TRYING left
            respondingHandler.setHandlerState(PROVISIONAL);
            logger.debug("Received Trying response, PROVISIONAL status set");
        }

        return this;
    }


    @Override
    protected State processTimeout(TimeoutEvent timeoutEvent)
            throws SendResponseError, UnexpectedSipMessageError {

        if (timeoutEvent.isServerTransaction()) {
            // server transaction timeouts are unexpected, there is no logic predefined for this
            ServerTransaction st = timeoutEvent.getServerTransaction();
            logger.error("ServerTransaction timeout for request {}", st.getRequest().getMethod());
            throw new SendResponseError("Unexpected ServerTransaction timeout for " + st.getRequest());
        } else {
            Request request = timeoutEvent.getClientTransaction().getRequest();
            if(request.getMethod().equals(Request.INVITE)){
                // client transaction timeout, in this state this is considered as 408 (Request Timeout)
                B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(timeoutEvent.getClientTransaction());

                Dialog timeoutDialog = timeoutEvent.getClientTransaction().getDialog();
                logger.info("INVITE timeout, callID: {}", timeoutDialog.getCallId());
                // client timeout for INVITE means no future DialogTerminated event will come for this dialog
                // remove it from the map
                if(checkAndRemoveSession(timeoutDialog)){
                    logger.debug("This was the last dialog, session removed");
                    return this;
                }

                // check if stop or skip logic for this ruleset
                OrchestrationRuleset.ErrorLogic logic =
                                            context.itsRuleset.getResponseHandling(Response.REQUEST_TIMEOUT);
                logger.debug("Timeout event, applying logic: {}", logic);

                // find session handler from client transaction
                B2BDialogsHandler handlerToRespond = respondingHandler.getPreviousHandler();

                if (logic == OrchestrationRuleset.ErrorLogic.STOP) {
                    // reset current handler to the one that would get the response
                    context.setCurrentHandler(handlerToRespond);
                    checkAndApplyRollback(handlerToRespond);
                    return sendNewResponseBack(handlerToRespond, Response.REQUEST_TIMEOUT);

                } else if (skipNotPossible(respondingHandler)) {

                    return sendNewResponseBack(handlerToRespond, Response.REQUEST_TIMEOUT);

                } else {
                    // SKIP logic
                    return executeSkipLogic(respondingHandler);
                }
            } else {
                logger.error("Timeout on client transaction for {}", request.getMethod());
                throw new UnexpectedSipMessageError("ClientTransaction timeout " + request.getMethod());
            }
        }

    }


    /**
     * This checks if skip logic not possible due to:
     * - error response from IM-SCF
     * - error response excluding last and only AS from the chain
     *
     * @param respondingAs - handler that responded
     * @return true if not possible to execute skip logic and error should be forwarded back
     */
    private boolean skipNotPossible(B2BDialogsHandler respondingAs) {
        return respondingAs.isImScf() ||
                (respondingAs.getPreviousHandler().isImScf() && respondingAs.getNextHandler().isImScf());
    }


    /**
     * Executes skip logic for the response.
     */
    private State executeSkipLogic(B2BDialogsHandler respondingAs) throws SendResponseError {
        logger.debug("Excluding Handler {} from orchestration within this session", respondingAs);

        B2BDialogsHandler previousAs = respondingAs.getPreviousHandler();
        B2BDialogsHandler nextAs = respondingAs.getNextHandler();

        // exclude failed AS from the chain, make the adjacent ASs point to each other
        previousAs.setNextHandler(nextAs);
        nextAs.setPreviousHandler(previousAs);

        // failedAs removed from the chain, indicate its successor as next handler
        return continueSetupWithNextAsOrImScf(context.getLastIncomingInvite(), nextAs);
    }


    /**
     * Proceed with next AS (or Im-Scf) to setup the call (send INVITE)
     *
     * @param incomingRequest - the request from previous node
     * @param nextHandler - next AS (or IMSCF) to INVITE
     *
     * @return next state
     */
    private State continueSetupWithNextAsOrImScf(Request incomingRequest, B2BDialogsHandler nextHandler)
                        throws SendResponseError {
        logger.trace("continueSetupWithNextAsOrImScf");

        // next handler must be set as current one in order to keep track of
        // of the next AS to INVITE
        context.setCurrentHandler(nextHandler);

        logger.trace("Next handler to forward INVITE: {}", nextHandler);

        try {
            nextHandler.processOutgoingInvite(incomingRequest);
            nextHandler.setHandlerState(INVITED);
            context.addDialog(nextHandler.getOutgoingDialog());

        } catch (UnrecoverableError e) {
            logger.warn("Error when sending INVITE request", e);

            // Response.SERVICE_UNAVAILABLE - default error code for fatal errors like request sending exception
            OrchestrationRuleset.ErrorLogic logic = context.itsRuleset.getResponseHandling(Response.SERVICE_UNAVAILABLE);
            logger.debug("Request Error, applying logic: {}", logic);

            B2BDialogsHandler handlerToRespond = nextHandler.getPreviousHandler();

            if (logic == OrchestrationRuleset.ErrorLogic.STOP){
                // reset current handler to the one that would get the response
                context.setCurrentHandler(handlerToRespond);
                checkAndApplyRollback(handlerToRespond);
                return sendNewResponseBack(handlerToRespond, Response.SERVICE_UNAVAILABLE);

            } else if (skipNotPossible(nextHandler)) {
                return sendNewResponseBack(handlerToRespond, Response.SERVICE_UNAVAILABLE);

            } else {
                return executeSkipLogic(nextHandler);
            }
        }

        if (nextHandler.isImScf()) {
            return new WaitingForImScfState(session);
        } else {
            // continue handling with this state
            return this;
        }
    }


    private State processResponseSessionProgress(ResponseEvent responseEvent,
                                                 B2BDialogsHandler respondingHandler,
                                                 B2BDialogsHandler previousHandler)
                                throws SendResponseError, UnrecoverableError {
        logger.trace("processResponseSessionProgress from: {}", respondingHandler);

        Response response = responseEvent.getResponse();
        RequireHeader requireHeader = (RequireHeader) response.getHeader(RequireHeader.NAME);
        boolean isReliable = requireHeader != null && requireHeader.getOptionTag().equalsIgnoreCase(TAG_100_REL);

        ExtensionHeader xAsHeader = (ExtensionHeader) response.getHeader(X_AS);
        if(xAsHeader == null || "false".equals(xAsHeader.getValue()) ){
            // this is a regular 183 response, send it back without special treatment
            if(isReliable) {
                logger.debug("Reliable 183 response, forward to handler: {}", respondingHandler.getPreviousHandler());
                previousHandler.forwardReliableProvisionalResponse(response);
                context.setReliableResponseProcessing(true);
                respondingHandler.setLastSessionProgressResponse(response);
            } else {
                logger.debug("Basic provisional response: {}, forwarding back", response.getStatusCode());
                previousHandler.forwardResponse(response, previousHandler.getIncomingDialog());
            }
            return this;
        }

        // this is not a special response
        if(isReliable){
            // find session handler from client transaction
            respondingHandler.setHandlerState(SESSION_PROGRESS_REPORTED);
            respondingHandler.setLastSessionProgressResponse(response);

            if(previousHandler.getHandlerState() == SESSION_PROGRESS_CONFIRMED){
                logger.trace("Previous AS already reported SessionInProgress, proceed with PRACK");
                respondingHandler.processOutgoingPrack();

            } else {
                logger.debug("Forward 183 response back to previous handler: {}", respondingHandler.getPreviousHandler());
                previousHandler.forwardReliableProvisionalResponse(response);

            }
        } else {
            logger.debug("Unreliable special provisional response: {}, forwarding back", response.getStatusCode());
            previousHandler.forwardResponse(response, previousHandler.getIncomingDialog());
        }

        return this;
    }


    private State processResponseOK(ResponseEvent responseEvent) throws SendResponseError {
        logger.trace("processResponseOK");

        // find session handler from client transaction
        B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(responseEvent.getClientTransaction());

        CSeqHeader cSeq = (CSeqHeader) responseEvent.getResponse().getHeader(CSeqHeader.NAME);
        if(cSeq.getMethod().equals(Request.INVITE)) {
            respondingHandler.setHandlerState(ANSWERED);
            B2BDialogsHandler handlerToPassResponse = respondingHandler.getPreviousHandler();
            logger.debug("INVITE response received, next Handler to forward response: {}", handlerToPassResponse);

            handlerToPassResponse.forwardResponse(responseEvent.getResponse(),
                    handlerToPassResponse.getIncomingDialog());

        } else if(cSeq.getMethod().equals(Request.INFO)) {

            processInfoResponse(responseEvent);

        } else if(cSeq.getMethod().equals(Request.BYE)) {
            logger.debug("BYE response received, could happen as a result of CANCEL");

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

        } else if(cSeq.getMethod().equals(Request.CANCEL)) {

            logger.debug("CANCEL response received, previous handler already answered with 200 OK, no action");

        } else {
            // PRACK response
            B2BDialogsHandler handlerToPassResponse = respondingHandler.getPreviousHandler();

            if(context.isReliableResponseProcessing()){
                logger.debug("Regular PRACK response, next Handler to forward response: {}",  handlerToPassResponse);
                handlerToPassResponse.forwardPrackResponse(responseEvent.getResponse(),
                        handlerToPassResponse.getIncomingDialog());

                if(handlerToPassResponse.isImScf()){
                    context.setReliableResponseProcessing(false);
                }

                return this;
            }

            if(handlerToPassResponse.getHandlerState() != SESSION_PROGRESS_CONFIRMED){
                // previous handler expects PRACK response, forward it back
                logger.debug("PRACK response received, next Handler to forward response: {}", handlerToPassResponse);
                handlerToPassResponse.forwardPrackResponse(responseEvent.getResponse(),
                        handlerToPassResponse.getIncomingDialog());
                // mark previous AS handler as SESSION_PROGRESS_CONFIRMED to block further 183 responses from next ASs
                handlerToPassResponse.setHandlerState(SESSION_PROGRESS_CONFIRMED);

            } else {
                logger.debug("Previous handler already reported SessionProgress, do not pass response back.");
            }
        }

        return this;
    }
}

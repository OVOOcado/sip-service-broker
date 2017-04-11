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
import pl.ovoo.slee.resource.sip.broker.service.sessionfsm.SessionContext;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.InvalidArgumentException;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionUnavailableException;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATED;

/**
 * This represents B2B logical entity.
 * Should be used to send requests/responses on incoming/outgoing dialogs related to either AS (Service) or IM-SCF.
 */
public abstract class B2BDialogsHandler {
    /**
     * The state of the handler (indicating its corresponding node state)
     */
    public enum HandlerState {
        INITIAL, // initial state, no 183 response processed yet
        INVITED, // INVITE sent towards the AS
        SET_TO_CANCEL, // Waiting for provisional response (to send CANCEL afterwards)
        PROVISIONAL, // Any provisional response received
        SESSION_PROGRESS_REPORTED, // AS responded with 183, it still waits for PRACK
        SESSION_PROGRESS_CONFIRMED, // AS which _incoming_ leg has alread processed 183
        ANSWERED, // after 200 OK received from node
        ACKED, // after ACK sent towards the node
        TERMINATING, // BYE sent that leads to forward chain termination
        TERMINATED // after sending CANCEL/BYE or when error response received
    }

    protected final SessionContext context;
    protected final Logger logger;
    final ServiceProvider serviceProvider;
    // reference wrapper associated to this handler
    final HandlerReferenceWrapper itsReferenceWrapper;

    private ClientTransaction lastClientTransaction;
    private ServerTransaction lastServerTransaction;
    private Dialog itsIncomingDialog;
    private Dialog itsOutgoingDialog;
    private Request lastIncomingRequest;
    private Request lastOutgoingInvite;

    // required separate members for PRACK
    private ServerTransaction lastPrackServerTransaction;
    private Request lastIncomingPrackRequest;

    // required separate members for INFO
    private ServerTransaction lastInfoServerTransaction;
    private Request lastIncomingInfoRequest;

    // these endpoints might change when adjacent AS is skipped from orchestration
    // this is the next AS in forward direction
    private B2BDialogsHandler nextAs;
    // this is the next AS in backward direction
    private B2BDialogsHandler previousAs;
    private HandlerState handlerState = HandlerState.INITIAL;

    // last received session progress response from this handler
    private Response lastSessionProgressResponse;

    // indicates if this node incoming leg is processing reliable response
    private boolean isPendingReliableResponse;

    /**
     * @param context         - orchestrated session context
     * @param serviceProvider - service provider this handler will use for outgoing messages
     */
    public B2BDialogsHandler(SessionContext context, ServiceProvider serviceProvider) {
        this.context = context;
        this.serviceProvider = serviceProvider;
        itsReferenceWrapper = new HandlerReferenceWrapper(context.itsSession, this);
        logger = context.getSessionLogger(getClass());
    }


    /**
     * Indicates if this handler represents IM-SCF (A or B leg)
     *
     * @return true if this is an IM-SCF entity
     */
    public abstract boolean isImScf();


    /**
     * Forwards generic INVITE/BYE/INFO response to this handler
     *
     * @param responseToPass - the original response to pass/forward
     * @param dialog         - dialog to send response on
     *
     * @throws SendResponseError in case of any response sending exception
     */
    public abstract void forwardResponse(Response responseToPass, Dialog dialog) throws SendResponseError;


    /**
     * Forwards INVITE request towards this handler
     *
     * @param inviteRequestToPass - incoming INVITE request (from previous dialog)
     *
     * @throws UnrecoverableError in case of any outgoing request exception
     */
    public abstract void processOutgoingInvite(Request inviteRequestToPass) throws UnrecoverableError;


    /**
     * Forwards PRACK response to this handler
     *
     * @param responseToPass - the response to pass/forward
     * @param dialog         - dialog to send response on
     */
    public void forwardPrackResponse(Response responseToPass, Dialog dialog) throws SendResponseError {
        logger.trace("forwardPrackResponse towards: {}", this);

        try {
            Response prackResp = MessageUtils.createForwardedResponse(responseToPass, dialog,
                    lastIncomingPrackRequest, context.getMessageFactory(), context.brokerContext
                            .getBrokerContactHeader(), logger);

            lastPrackServerTransaction.sendResponse(prackResp);
            lastPrackServerTransaction.setApplicationData(itsReferenceWrapper);

            if (responseToPass.getStatusCode() >= Response.OK) {
                lastPrackServerTransaction = null;
                lastIncomingPrackRequest = null;
            }

        } catch (SipException | ParseException | InvalidArgumentException e) {
            logger.error("Unable to send PRACK response towards {}", this, e);
            throw new SendResponseError("Error while trying to send PRACK response back", e);
        }
    }


    /**
     * Forwards INFO response to this handler
     *
     * @param responseToPass - the response to pass/forward
     * @param dialog         - dialog to send response on
     */
    public void forwardInfoResponse(Response responseToPass, Dialog dialog) throws SendResponseError {
        logger.debug("forwardInfoResponse towards: {}", this);

        try {
            Response infoResp = MessageUtils.createForwardedResponse(responseToPass, dialog,
                    lastIncomingInfoRequest, context.getMessageFactory(),
                    context.brokerContext.getBrokerContactHeader(), logger);

            lastInfoServerTransaction.sendResponse(infoResp);
            lastInfoServerTransaction.setApplicationData(itsReferenceWrapper);

            if (responseToPass.getStatusCode() >= Response.OK) {
                lastInfoServerTransaction = null;
                lastIncomingInfoRequest = null;
            }

        } catch (SipException | ParseException | InvalidArgumentException e) {
            logger.error("Unable to send INFO response towards {}", this, e);
            throw new SendResponseError("Error while trying to send INFO response", e);
        }
    }


    /**
     * Terminates incoming INFO/PRACK requests transactions.
     * Should be used in conjunction with error response sent towards this handler.
     */
    public void respondToPendingRequestsOnDialogTerminatingResponse(){
        logger.trace("respondToPendingRequestsOnDialogTerminatingResponse");

        try {
            if(lastIncomingInfoRequest != null){
                logger.debug("Found pending INFO request transaction to terminate");
                lastInfoServerTransaction.sendResponse(
                        createErrorResponse(Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST, lastIncomingInfoRequest));

                lastInfoServerTransaction = null;
                lastIncomingInfoRequest = null;
            }

            if(lastIncomingPrackRequest != null){
                logger.debug("Found pending PRACK request transaction to terminate");
                lastPrackServerTransaction.sendResponse(
                        createErrorResponse(Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST, lastIncomingPrackRequest));

                lastPrackServerTransaction = null;
                lastIncomingPrackRequest = null;
            }

        } catch (SipException | InvalidArgumentException e) {
            logger.warn("Error while terminating transaction ", e);
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
            throw new IllegalArgumentException("Unexpected response code: " + statusCode, ignored);
        }
    }


    /**
     * Forwards reliable provisional response to this handler
     *
     * @param responseToPass - the response to pass/forward
     */
    public void forwardReliableProvisionalResponse(Response responseToPass) throws SendResponseError {
        logger.trace("forwardReliableProvisionalResponse towards: {}", this);
        if(isPendingReliableResponse){
            logger.warn("{} B leg already processing reliable response, skip forwarding");
            return;
        }

        try {
            Response sessionProgress = MessageUtils.createForwardedReliableResponse(responseToPass, getIncomingDialog(),
                    context.brokerContext.getBrokerContactHeader(), logger);

            getIncomingDialog().sendReliableProvisionalResponse(sessionProgress);
            isPendingReliableResponse = true;

        } catch (SipException | ParseException | InvalidArgumentException e) {
            logger.error("Unable to send reliable provisional response towards {}: {}", this, e.getMessage());
            throw new SendResponseError("Unable to send reliable provisional response", e);
        }
    }


    public void processOutgoingPrack() throws UnrecoverableError {
        logger.trace("processOutgoingPrack");

        try {
            Request prackRequest = getOutgoingDialog().createPrack(getLastSessionProgressResponse());

            ClientTransaction ct = serviceProvider.getNewClientTransaction(prackRequest);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            getOutgoingDialog().sendRequest(ct);

            // associate transaction with handler
            ct.setApplicationData(itsReferenceWrapper);
        } catch (SipException e) {
            logger.warn("Unable to send Prack", e);
            throw new UnrecoverableError(e);
        }
    }

    public void sendCancel() throws UnrecoverableError {
        logger.trace("sendCancel");

        try {
            Request cancelRequest = getLastClientTransaction().createCancel();

            ClientTransaction ct = serviceProvider.getNewClientTransaction(cancelRequest);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            ct.sendRequest();

            logger.trace("Sent CANCEL request:\n{}", cancelRequest);

            // associate session/handler references
            ct.setApplicationData(itsReferenceWrapper);
        } catch (SipException e) {
            logger.warn("Unable to send Cancel", e);
            throw new UnrecoverableError(e);
        }
    }


    /**
     * Sends terminating BYE request in case of error
     */
    public void sendBye() throws UnrecoverableError {
        logger.trace("sendBye");
        try {
            Request byeRequest = getOutgoingDialog().createRequest(Request.BYE);
            ClientTransaction ct = serviceProvider.getNewClientTransaction(byeRequest);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            getOutgoingDialog().sendRequest(ct);

            logger.trace("Sent BYE request:\n{}", byeRequest);

            // associate transaction with handler
            ct.setApplicationData(itsReferenceWrapper);
        } catch (SipException e) {
            logger.warn("Unable to send dialog request", e);
            throw new UnrecoverableError(e);
        }
    }


    /**
     * Sends terminating BYE request in case of error
     * This method does not throw exceptions.
     */
    public void sendTerminatingBye() {
        logger.trace("sendTerminatingBye");
        try {
            sendBye();
        } catch (UnrecoverableError e) {
            logger.warn("Error while sending terminating BYE towards {}", this, e);
        } finally {
            setHandlerState(TERMINATED);
        }
    }

    /**
     * Sends terminating CANCEL request in case of error
     */
    public void sendTerminatingCancel() {
        logger.trace("sendTerminatingCancel");

        try {
            Request cancel = getLastClientTransaction().createCancel();
            ClientTransaction ct = serviceProvider.getNewClientTransaction(cancel);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            ct.sendRequest();

            // associate transaction with handler
            ct.setApplicationData(itsReferenceWrapper);

            logger.trace("Terminating CANCEL sent");
        } catch (SipException e) {
            logger.warn("Error while sending terminating CANCEL towards {}", this, e);
        } finally {
            setHandlerState(TERMINATED);
        }

    }


    /**
     * Process the request according to the rules
     * Dialog is passed as argument in order to know the direction of the BYE (either caller or callee)
     *
     * @param incomingBye - request incoming from previous AS (or IM-SCF)
     * @param dialog      - dialog on which to send BYE towards IM-SCF
     */
    public void forwardBye(Request incomingBye, Dialog dialog) throws UnrecoverableError {
        logger.trace("forwardBye");
        try {
            Request newByeRequest = MessageUtils.createOnDialogRequest(incomingBye, dialog, context.brokerContext
                    .getBrokerContactHeader(), logger);

            ClientTransaction ct = serviceProvider.getNewClientTransaction(newByeRequest);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            dialog.sendRequest(ct);

            // associate session/handler references
            ct.setApplicationData(itsReferenceWrapper);

            logger.trace("BYE sent to the IM-SCF");
        } catch (ParseException | SipException e) {
            logger.warn("Unable to send BYE", e);
            throw new UnrecoverableError(e);
        }

    }

    /**
     * Send request on given dialog
     *
     * @param request           - request to send
     * @param dialog            - dialog where to forward the request
     * @param specialInfoSender - B2BDialogHandler instance that initiated this special request
     */
    public void sendRequestOnDialog(Request request, Dialog dialog, B2BDialogsHandler specialInfoSender) throws
            UnrecoverableError {
        logger.trace("sendRequestOnDialog");
        try {
            ClientTransaction ct = serviceProvider.getNewClientTransaction(request);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            dialog.sendRequest(ct);

            // associate transaction with handler
            itsReferenceWrapper.setSpecialInfoSender(specialInfoSender);
            ct.setApplicationData(itsReferenceWrapper);
            logger.trace("Request sent to the next AS in the chain");

        } catch (SipException e) {
            logger.warn("Unable to send dialog request", e);
            throw new UnrecoverableError(e);
        }
    }

    public void setNextHandler(B2BDialogsHandler next) {
        nextAs = next;
    }

    public void setPreviousHandler(B2BDialogsHandler previous) {
        previousAs = previous;
    }

    public B2BDialogsHandler getNextHandler() {
        return nextAs;
    }

    public B2BDialogsHandler getPreviousHandler() {
        return previousAs;
    }

    /**
     * Returns last server transaction on the incoming dialog or null if not exists.
     *
     * @return last ServerTransaction on the incoming dialog
     */
    public ServerTransaction getLastServerTransaction() {
        return lastServerTransaction;
    }

    /**
     * Sets last server transaction on the incoming dialog.
     *
     * @return last ServerTransaction on the incoming dialog
     */
    public void setLastServerTransaction(ServerTransaction tx) {
        lastServerTransaction = tx;
    }

    /**
     * Returns true if last ServerTransaction on the incoming dialog exists
     *
     * @return
     */
    public boolean isLastServerTransaction() {
        return lastServerTransaction != null;
    }

    /**
     * Returns incoming SIP Dialog reference.
     */
    public Dialog getIncomingDialog() {
        return itsIncomingDialog;
    }

    /**
     * Sets its incoming SIP Dialog
     *
     * @param dialog
     */
    public void setIncomingDialog(Dialog dialog) {
        itsIncomingDialog = dialog;
    }

    /**
     * Returns outgoing SIP Dialog reference.
     */
    public Dialog getOutgoingDialog() {
        return itsOutgoingDialog;
    }

    /**
     * Sets its outgoing SIP Dialog
     *
     * @param dialog
     */
    public void setOutgoingDialog(Dialog dialog) {
        itsOutgoingDialog = dialog;
    }

    /**
     * Indicates if incoming Dialog is established with this handler.
     *
     * @return true - if incoming dialog established
     */
    public boolean isIncomingDialogEstablished() {
        return itsIncomingDialog != null;
    }

    /**
     * Indicates if outgoing Dialog is established with this handler.
     *
     * @return true - if outgoing dialog established
     */
    public boolean isOutgoingDialogEstablished() {
        return itsOutgoingDialog != null;
    }

    /**
     * Sets last incoming Request to this handler
     *
     * @param request
     */
    public void setLastIncomingRequest(Request request) {
        lastIncomingRequest = request;
    }

    /**
     * Returns last incoming Request for this handler
     *
     * @return last incoming Request
     */
    public Request getLastIncomingRequest() {
        return lastIncomingRequest;
    }

    /**
     * Returns last outgoing INVITE sent towards this handler
     *
     * @return last outgoing Request
     */
    public Request getLastOutgoingInvite() {
        return lastOutgoingInvite;
    }

    /**
     * Sets last outgoing INVITE sent towards this handler
     *
     * @param request
     */
    public void setLastOutgoingInvite(Request request) {
        this.lastOutgoingInvite = request;
    }

    public void setHandlerState(HandlerState handlerState) {
        this.handlerState = handlerState;
    }

    public HandlerState getHandlerState() {
        return handlerState;
    }

    public void setLastSessionProgressResponse(Response sessionProgress) {
        lastSessionProgressResponse = sessionProgress;
    }

    public Response getLastSessionProgressResponse() {
        return lastSessionProgressResponse;
    }

    public void setLastIncomingInfoRequest(Request lastIncomingInfoRequest) {
        this.lastIncomingInfoRequest = lastIncomingInfoRequest;
    }

    public void setLastInfoServerTransaction(ServerTransaction lastInfoServerTransaction) {
        this.lastInfoServerTransaction = lastInfoServerTransaction;
    }

    public void setLastIncomingPrackRequest(Request lastIncomingPrackRequest) {
        this.lastIncomingPrackRequest = lastIncomingPrackRequest;
    }

    public void setLastPrackServerTransaction(ServerTransaction lastPrackServerTransaction) {
        this.lastPrackServerTransaction = lastPrackServerTransaction;
    }

    public void setLastClientTransaction(ClientTransaction lastClientTransaction) {
        this.lastClientTransaction = lastClientTransaction;
    }

    public ClientTransaction getLastClientTransaction() {
        return lastClientTransaction;
    }

    public ServerTransaction getNewServerTransaction(Request request) throws TransactionAlreadyExistsException,
            TransactionUnavailableException {
        return serviceProvider.getNewServerTransaction(request);
    }

    public HandlerReferenceWrapper getReferenceWrapper() {
        return itsReferenceWrapper;
    }

    /**
     * Set pending reliable response. To be called after PRACK received on this handler B leg.
     * Set this flag to avoid duplicated reliable responses to be forwarded when received
     * from the sip stack (A leg of the next handler)
     *
     * @param pendingReliableResponse - pending reliable response status
     */
    public void setPendingReliableResponse(boolean pendingReliableResponse) {
        isPendingReliableResponse = pendingReliableResponse;
    }
}

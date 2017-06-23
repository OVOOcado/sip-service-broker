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

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.TimeoutEvent;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * SessionEndingState - handles BYEs and 200 OK responses after some party ends the whole session.
 */
public class SessionEndingState extends SessionStateBase {

    public SessionEndingState(OrchestratedSession session) {
        super(session);
    }

    protected State handleBye(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleBye");

        Request incomingBye = event.getRequest();
        logger.trace("Incoming BYE:\n{}", incomingBye);
        // find associated handler
        HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) event.getDialog().getApplicationData();
        B2BDialogsHandler byeHandler = wrapper.getDialogHandler();

        // store server transaction for current AS
        ServerTransaction serverTransaction = event.getServerTransaction();
        byeHandler.setLastServerTransaction(serverTransaction);
        byeHandler.setLastIncomingRequest(incomingBye);

        Dialog nextDialog;
        B2BDialogsHandler handlerToSendBye;
        if(context.isByeInitiatedByCallingParty()) {
            // BYE goes towards Called party
            logger.trace("BYE request initiated by calling party, pass requests forward int the chain");
            handlerToSendBye = byeHandler.getNextHandler();
            nextDialog = handlerToSendBye.getOutgoingDialog();
        } else {
            // BYE goes towards Calling party
            logger.trace("BYE request initiated by called party, pass requests backward int the chain");
            handlerToSendBye = byeHandler.getPreviousHandler();
            nextDialog = handlerToSendBye.getIncomingDialog();

            // reset current handler to the one that would get the response
            context.setCurrentHandler(handlerToSendBye);
        }

        logger.debug("Found next Handler to forward BYE request: {}", handlerToSendBye);

        handlerToSendBye.forwardBye(incomingBye, nextDialog);

        return this;
    }

    /**
     * This method process the sip Response from IM-SCF (in this state)
     *
     * @param event - response event
     */
    @Override
    protected State handleResponse(ResponseEvent event) throws SendResponseError {
        logger.debug("handleResponse, statusCode: {}", event.getResponse().getStatusCode());
        Response response = event.getResponse();

        if( event.getResponse().getStatusCode() == Response.OK){
            return processResponseOK(event);

        } else {
            throw new SendResponseError("Invalid response received after BYE request: " + response.getStatusCode());
        }
    }


    private State processResponseOK(ResponseEvent event) throws SendResponseError {
        logger.trace("processResponseOK");

        ClientTransaction ctx = event.getClientTransaction();
        // find session handler from client transaction
        B2BDialogsHandler currentHandler = fetchB2BHandlerFromClientTx(ctx);

        B2BDialogsHandler handlerToResponse;
        if(context.isByeInitiatedByCallingParty()) {
            // BYE response goes back towards Calling party
            handlerToResponse = currentHandler.getPreviousHandler();
            logger.debug("Successful response received, previous handler to forward response: {}", handlerToResponse);
            handlerToResponse.forwardResponse(event.getResponse(), handlerToResponse.getIncomingDialog());

            // reset current handler to the one that would get the response
            context.setCurrentHandler(handlerToResponse);

        } else {
            // BYE response goes back towards Called party
            handlerToResponse = currentHandler.getNextHandler();
            logger.debug("Successful response received, next handler to forward response: {}", handlerToResponse);
            handlerToResponse.forwardResponse(event.getResponse(),
                                                            handlerToResponse.getOutgoingDialog());
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
        logger.trace("handleInvite in SessionEndingState, jump to ChainingState and execute");

        // rollback is necessary due to possible new INVITE from AS being forwarded BYE request

        checkAndApplyRollback(context.getCurrentHandler());

        // ugly hack to handle INVITE in ChainingState without preprocessing
        // if event handled as handleEvent, it would lead to INVITE preprocessing (already done for this state)
        ChainingState state = new ChainingState(session);
        return state.handleInvite(event);
    }

    @Override
    protected State processTimeout(TimeoutEvent timeoutEvent) throws SendResponseError {
        logger.trace("processTimeout");

        if (timeoutEvent.isServerTransaction()) {
            // server transaction timeouts are unexpected, there is no logic predefined for this
            ServerTransaction st = timeoutEvent.getServerTransaction();
            logger.error("ServerTransaction timeout for request {}", st.getRequest().getMethod());
            throw new SendResponseError("Unexpected ServerTransaction timeout for " + st.getRequest());
        } else {

            if(timeoutEvent.getClientTransaction().getRequest().getMethod().equals(Request.BYE)){

                // find session handler from client transaction
                B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(timeoutEvent.getClientTransaction());

                B2BDialogsHandler handlerToRespond;
                if(context.isByeInitiatedByCallingParty()) {
                    logger.trace("ByeInitiatedByCallingParty");
                    // BYE response goes back towards Calling party
                    handlerToRespond = respondingHandler.getPreviousHandler();
                } else {
                    logger.trace("ByeInitiatedByCalledParty");
                    // BYE response goes back towards Called party
                    handlerToRespond = respondingHandler.getNextHandler();
                }

                logger.debug("handler to send response: {}", handlerToRespond);
                Response response = createNewResponse(Response.OK, handlerToRespond.getLastIncomingRequest());
                handlerToRespond.sendNewResponse(response);
            }
        }

        return this;
    }
}

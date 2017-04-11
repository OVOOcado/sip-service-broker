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
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.Dialog;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.header.CSeqHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.ACKED;

/**
 * Session ongoing, waiting for BYE
 */
public class SessionOngoingState extends SessionStateBase {

    public SessionOngoingState(OrchestratedSession session) {
        super(session);
    }

    protected State handleBye(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleBye");

        Request incomingBye = event.getRequest();
        logger.debug("Incoming BYE:\n{}", incomingBye);

        ServerTransaction serverTransaction = event.getServerTransaction();
        B2BDialogsHandler byeSendingHandler = fetchB2BHandlerFromDialog(event.getDialog());

        if (event.getDialog() == byeSendingHandler.getIncomingDialog()) {
            logger.debug("BYE initiated by calling party {}, pass requests forward int the chain", byeSendingHandler);

            context.setByeInitiatedByCallingParty(true);
            byeSendingHandler.setLastServerTransaction(serverTransaction);
            byeSendingHandler.setLastIncomingRequest(incomingBye);

            B2BDialogsHandler nextByeHandler = byeSendingHandler.getNextHandler();
            logger.debug("Found next Handler in the chain to handle BYE: {}", nextByeHandler);

            nextByeHandler.forwardBye(incomingBye, nextByeHandler.getOutgoingDialog());

        } else {
            logger.debug("BYE initiated by called party {}, pass requests backward int the chain", byeSendingHandler);

            context.setByeInitiatedByCallingParty(false);
            byeSendingHandler.setLastServerTransaction(serverTransaction);
            byeSendingHandler.setLastIncomingRequest(incomingBye);

            B2BDialogsHandler previousByeHandler = byeSendingHandler.getPreviousHandler();
            logger.debug("Found previous Handler in the chain to handle BYE: {}", previousByeHandler);

            previousByeHandler.forwardBye(incomingBye, previousByeHandler.getIncomingDialog());

            // reset current handler to the one that would get the response
            context.setCurrentHandler(previousByeHandler);

        }

        context.brokerContext.getUsageParameters().incrementSuccessfulSessionsCount(1);
        return new SessionEndingState(session);
    }

    /**
     * This method process the sip INFO Request
     *
     * @param event - INFO request event
     */
    @Override
    protected State handleInfo(RequestEvent event) throws UnrecoverableError {
        logger.trace("handleInfo");
        return handleCommonInfoLogic(event);
    }


    /**
     * This method process the sip Response
     *
     * @param event - Response event
     */
    @Override
    protected State handleResponse(ResponseEvent event) throws SendResponseError, UnexpectedSipMessageError {
        logger.debug("handleResponse, statusCode: {}", event.getResponse().getStatusCode());
        Response response = event.getResponse();

        if (response.getStatusCode() == Response.OK) {
            // find session handler from client transaction
            CSeqHeader cseq = (CSeqHeader) event.getResponse().getHeader(CSeqHeader.NAME);
            if(cseq.getMethod().equals(Request.INFO)) {
                processInfoResponse(event);
            } else {
                throw new UnexpectedSipMessageError("Unexpected Response in SessionOngoingState: " + cseq.getMethod());
            }

            return this;

        } else {
            throw new UnexpectedSipMessageError("Unexpected error in SessionOngoingState: " + response.getStatusCode());
        }
    }


    @Override
    protected State handleAck(RequestEvent requestEvent) throws UnrecoverableError {
        logger.debug("Late ACK to INVITE OK, create and send ACK to the next Handler");
        try {
            Dialog dialog = requestEvent.getDialog();
            Request receivedAck = requestEvent.getRequest();

            // find associated handler
            HandlerReferenceWrapper wrapper = (HandlerReferenceWrapper) dialog.getApplicationData();
            B2BDialogsHandler currentAckHandler = wrapper.getDialogHandler();

            B2BDialogsHandler nextAckHandler = currentAckHandler.getNextHandler();
            logger.debug("Found next Handler to forward ACK: {}", nextAckHandler);
            if(nextAckHandler.getHandlerState() == ACKED){
                logger.debug("ACK already sent towards next handler, skip forwarding");
                return this;
            }

            long cSeq = ((CSeqHeader) nextAckHandler.getLastOutgoingInvite().getHeader(CSeqHeader.NAME)).getSeqNumber();

            Request ack = MessageUtils.createAck(receivedAck, cSeq, nextAckHandler.getOutgoingDialog(),
                                                    context.brokerContext.getBrokerContactHeader(), logger);
            nextAckHandler.getOutgoingDialog().sendAck(ack);
            return this;

        } catch (ParseException | InvalidArgumentException | SipException e){
            logger.warn("Unable to forward late ACK", e);
            throw new UnrecoverableError(e);
        }
    }

}

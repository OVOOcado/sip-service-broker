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
import pl.ovoo.slee.resource.sip.broker.service.OrchestratedSession;
import pl.ovoo.slee.resource.sip.broker.service.SendResponseError;

import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.TimeoutEvent;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.TERMINATED;

/**
 * EndState used only to handle DialogTerminated, DialogTimeout or TransactionTerminated events.
 * When all IM-SCF dialogs are terminated the session will be removed by SipMessageListener
 */
public class EndState extends SessionStateBase {

    public EndState(OrchestratedSession session) {
        super(session);
    }

    /**
     * This method handles the ACK, it only avoids unsupported event exception to be thrown from its base class.
     *
     * @param event
     *
     * @return this state
     */
    @Override
    protected State handleAck(RequestEvent event){
        logger.trace("handleAck, ignoring the event");
        return this;
    }

    /**
     * Timeout is possible in this state but no action required
     * DialogTimeoutEvent or DialogTerminatedEvent are the ones that finnish the session.
     * The above events are handled by base class
     *
     * @param timeoutEvent - timeout event
     *
     * @return this state
     */
    @Override
    protected State processTimeout(TimeoutEvent timeoutEvent) {
        logger.trace("TimeoutEvent received in EndState, still waiting for the Dialogs to terminate");
        return this;
    }


    /**
     * In this state responses are passed back in the chain.
     * This allows the chain to complete, for instance by passing response from IM-SCF B leg to CANCEL initiator.
     *
     * @param event - response event
     */
    @Override
    protected State handleResponse(ResponseEvent event) throws SendResponseError {
        logger.debug("handleResponse, statusCode: {}", event.getResponse().getStatusCode());
        B2BDialogsHandler respondingHandler = fetchB2BHandlerFromClientTx(event.getClientTransaction());
        B2BDialogsHandler previousHandler = respondingHandler.getPreviousHandler();

        logger.debug("Forwarding response back to {}", previousHandler);
        respondingHandler.setHandlerState(TERMINATED);
        previousHandler.forwardResponse( event.getResponse(), previousHandler.getIncomingDialog());

        return this;
    }

}

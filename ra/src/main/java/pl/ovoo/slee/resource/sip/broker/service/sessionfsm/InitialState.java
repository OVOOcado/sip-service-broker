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
import pl.ovoo.slee.resource.sip.broker.service.UnrecoverableError;

import javax.sip.RequestEvent;
import javax.sip.message.Request;
import javax.sip.message.Response;

import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.INVITED;
import static pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler.HandlerState.SESSION_PROGRESS_REPORTED;

/**
 * This is an initial state of the session.
 * In this state the broker:
 * - stores incoming request in the context
 * - creates first server transaction for IM-SCF
 * - sends first provisional response towards IM-SCF
 * - sends INVITE towards first AS in the chain
 */
public class InitialState extends SessionStateBase {

    public InitialState(OrchestratedSession session) {
        super(session);
    }

    // in InitialState only INVITE Request from ImScf is expected no matter what
    @Override
    protected State handleInvite(RequestEvent requestEvent) {
        logger.trace("handleIncomingRequest");

        try {
            // continue handling requests/responses with session setup state
            return sendFirstASRequest(requestEvent);
        } catch (UnrecoverableError e) {
            logger.error("Unable to send first INVITE", e);
            sendImmediateErrorResponse(Response.SERVER_INTERNAL_ERROR, requestEvent,
                    "ServiceBroker error: " + e.getMessage());
            return new EndState(session);
        }
    }

    private State sendFirstASRequest(RequestEvent requestEvent) throws UnrecoverableError {
        logger.trace("sendFirstASRequest");

        Request incomingRequest = requestEvent.getRequest();

        // initial INVITE -> current handler reference set first AS
        B2BDialogsHandler handlerToInvite = context.getFirstChainedAs();
        context.setCurrentHandler(handlerToInvite);
        logger.debug("Found next AS to handle: {}", handlerToInvite);

        handlerToInvite.processOutgoingInvite(incomingRequest);
        context.addDialog(handlerToInvite.getOutgoingDialog());
        handlerToInvite.setHandlerState(INVITED);

        return new ChainingState(session);
    }


}

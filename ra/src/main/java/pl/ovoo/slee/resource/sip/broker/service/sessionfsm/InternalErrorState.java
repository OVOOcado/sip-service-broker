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
import pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler;
import pl.ovoo.slee.resource.sip.broker.service.OrchestratedSession;
import pl.ovoo.slee.resource.sip.broker.service.UnrecoverableError;

import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.ResponseEvent;
import javax.sip.TimeoutEvent;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.EventObject;

/**
 * In this state broker handles internal error scenario.
 * Its only purpose is to log received responses until all IM-SCF dialogs are terminated.
 *
 */
public class InternalErrorState extends SessionStateBase {
    public InternalErrorState(OrchestratedSession session) {
        super(session);
    }

    public State handleEvent(EventObject event) {

        if (event instanceof DialogTimeoutEvent) {
            logger.debug("Received DialogTimeoutEvent in state: {}", getClass().getSimpleName());
            checkAndRemoveSession(((DialogTimeoutEvent) event).getDialog());

        } else if (event instanceof DialogTerminatedEvent) {
            logger.debug("Received DialogTerminatedEvent in state: {}", getClass().getSimpleName());
            checkAndRemoveSession(((DialogTerminatedEvent) event).getDialog());

        } else if (event instanceof TimeoutEvent) {
            TimeoutEvent timeoutEvent = (TimeoutEvent) event;
            if (!timeoutEvent.isServerTransaction()) {
                // client transaction timeout, in this state this is considered as 408 (Request Timeout)
                if(timeoutEvent.getClientTransaction().getRequest().getMethod().equals(Request.INVITE)){
                    Dialog timeoutDialog = timeoutEvent.getClientTransaction().getDialog();
                    logger.info("INVITE timeout, callID: {}", timeoutDialog.getCallId());
                    checkAndRemoveSession(timeoutDialog);
                } else {
                    logger.debug("Received Client transaction timeout for non-INVITE request, ignoring");
                }
            } else {
                logger.debug("Received Server transaction timeout , ignoring");
            }

        } else if (event instanceof ResponseEvent) {
            // in this state response might trigger postponed CANCEL (or BYE)
            ResponseEvent responseEvent = (ResponseEvent) event;
            B2BDialogsHandler handler = fetchB2BHandlerFromClientTx(responseEvent.getClientTransaction());
            if (handler.getHandlerState() == B2BDialogsHandler.HandlerState.SET_TO_CANCEL) {
                try {
                    // check status code
                    int statusCode = responseEvent.getResponse().getStatusCode();
                    if (statusCode < Response.OK) {
                        // provisional response, time to send CANCEL now
                        handler.sendCancel();
                    } else if (statusCode >= Response.OK && statusCode < Response.BAD_EXTENSION) {
                        // final response, sending BYE
                        handler.sendBye();
                    } else {
                        // ignoring other results, nothing to do
                        logger.debug("Received error response, no action, ignoring");
                    }

                } catch (UnrecoverableError e) {
                    // nothing to be done in case of errors
                    logger.debug("Error while trying to CANCEL/BYE leg", e);
                }

            } else {
                logger.debug("Received response does not indicate any action, ignoring");
            }

        } else {
            logger.debug("Received event to ignore in InternalErrorState: {}", event.getClass().getSimpleName());
        }
        return this;
    }
}

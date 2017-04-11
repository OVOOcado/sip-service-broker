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

import pl.ovoo.slee.resource.sip.broker.dispatcher.ServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.sessionfsm.SessionContext;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.header.RouteHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * This is a logical entity representing either A or B leg of the IM-SCF
 */
public class ImScfHandler extends B2BDialogsHandler {
    // logical name of this ImScf handler
    private final String name;

    public ImScfHandler(SessionContext context, ServiceProvider provider, String name) {
        super(context, provider);
        this.name = name;
    }


    /**
     * Process outgoing INVITE to the AS according to the rules
     *
     * @param inviteRequestToPass - incoming INVITE request (from previous dialog)
     */
    @Override
    public void processOutgoingInvite(Request inviteRequestToPass) throws UnrecoverableError {
        logger.trace("processOutgoingInvite");

        try {
            // pop first route (broker's one)
            inviteRequestToPass.removeFirst(RouteHeader.NAME);

            ListIterator<RouteHeader> incomingRoutes = inviteRequestToPass.getHeaders(RouteHeader.NAME);
            List<RouteHeader> outgoingRoutes = new ArrayList<>();

            // copy rest of the route headers
            while(incomingRoutes.hasNext()){
                outgoingRoutes.add(incomingRoutes.next());
            }

            if (outgoingRoutes.isEmpty()) {
                // empty incoming route headers, use default one
                outgoingRoutes.add(context.brokerContext.getDefaultImScfRouteHeader());
            }

            Request newInvite = MessageUtils.createInvite(context.brokerContext, inviteRequestToPass, outgoingRoutes,
                    logger, serviceProvider.getNewCallId());

            logger.debug("Sending request:\n{}", newInvite);

            ClientTransaction ct = serviceProvider.getNewClientTransaction(newInvite);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            ct.sendRequest();
            // INVITE request sent, there is a dialog to store
            setLastOutgoingInvite(newInvite);
            setLastClientTransaction(ct);
            setOutgoingDialog(ct.getDialog());

            // associate session/handler references
            ct.setApplicationData(itsReferenceWrapper);
            ct.getDialog().setApplicationData(itsReferenceWrapper);

            logger.trace("INVITE sent to the next AS in the chain");

        } catch (SipException | ParseException e) {
            logger.error("Unable to send INVITE", e);
            throw new UnrecoverableError(e);
        }
    }

    /**
     * Forwards INVITE/BYE/INFO response to this handler
     *
     * @param responseToPass - the response to pass/forward
     * @param dialog         - dialog to send response on
     */
    @Override
    public void forwardResponse(Response responseToPass, Dialog dialog) throws SendResponseError {
        logger.trace("forwardResponse towards {}", name);

        try {
            Response imScfResponse = MessageUtils.createForwardedResponse(responseToPass, dialog,
                    getLastIncomingRequest(), context.getMessageFactory(), context.brokerContext
                            .getBrokerContactHeader(), logger);

            // cease all pending transactions (PRACK, INFO)
            if(responseToPass.getStatusCode() >= Response.MULTIPLE_CHOICES){
                respondToPendingRequestsOnDialogTerminatingResponse();
            }

            // send response using its last server transaction
            ServerTransaction st = getLastServerTransaction();
            st.sendResponse(imScfResponse);
            st.setApplicationData(itsReferenceWrapper);

            logger.debug("Response sent towards IM-SCF");

            // null transaction in case of final response
            if (responseToPass.getStatusCode() >= Response.OK) {
                setLastServerTransaction(null);
                setLastIncomingRequest(null);
            }

        } catch (Exception e) {
            logger.error("Not possible to send response back to IM-SCF : {}", e.getMessage());
            throw new SendResponseError("Error while trying to send response back to IM-SCF", e);
        }
    }


    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean isImScf() {
        return true;
    }
}

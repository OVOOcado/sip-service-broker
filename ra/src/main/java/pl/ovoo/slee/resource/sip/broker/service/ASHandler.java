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
import pl.ovoo.slee.resource.sip.broker.service.config.Endpoint;
import pl.ovoo.slee.resource.sip.broker.service.sessionfsm.SessionContext;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.InvalidArgumentException;
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
 * This is an AS logical entity. I handles dialogs and in-call data related to this AS (service).
 */
public class ASHandler extends B2BDialogsHandler {
    // an endpoint selected for this service
    private final Endpoint endpoint;

    public ASHandler(Endpoint endpoint, SessionContext context, ServiceProvider serviceProvider) {
        super(context, serviceProvider);
        this.endpoint = endpoint;
    }

    /**
     * Process outgoing INVITE to the AS according to the rules
     *
     * @param inviteRequestToPass - incoming INVITE request (from previous dialog)
     */
    @Override
    @SuppressWarnings("unchecked")
    public void processOutgoingInvite(Request inviteRequestToPass) throws UnrecoverableError {
        logger.trace("processOutgoingInvite");

        try {
            ListIterator<RouteHeader> incomingRoutes = inviteRequestToPass.getHeaders(RouteHeader.NAME);
            List<RouteHeader> outgoingRoutes = new ArrayList<>();

            // as endpoint Route goes first -> on the top
            outgoingRoutes.add(endpoint.getRouteHeader());
            // then all incoming Route headers (including broker's own Route)
            while(incomingRoutes.hasNext()){
                outgoingRoutes.add(incomingRoutes.next());
            }

            Request newInvite = MessageUtils.createInvite(context.brokerContext, inviteRequestToPass, outgoingRoutes,
                    logger, serviceProvider.getNewCallId());

            logger.debug("Sending request:\n{}", newInvite);

            ClientTransaction ct = serviceProvider.getNewClientTransaction(newInvite);
            ct.setRetransmitTimer(context.brokerContext.outgoingRetransmitTimer);
            ct.sendRequest();
            // INVITE request sent, should have dialog now
            setLastOutgoingInvite(newInvite);
            setLastClientTransaction(ct);
            setOutgoingDialog(ct.getDialog());

            // associate dialog/transaction with handler
            ct.setApplicationData(itsReferenceWrapper);
            ct.getDialog().setApplicationData(itsReferenceWrapper);

            logger.trace("INVITE sent to the next AS in the chain");

        } catch (SipException | ParseException e) {
            logger.error("Unable to send INVITE", e);
            throw new UnrecoverableError(e);
        }
    }


    /**
     * Forwards INVITE/BYE response to this handler
     *
     * @param responseToPass - the response to pass/forward
     * @param dialog         - dialog to send response on
     */
    @Override
    public void forwardResponse(Response responseToPass, Dialog dialog) throws SendResponseError {
        logger.debug("forwardResponse towards: {}", endpoint.getAsAlias());

        if (isLastServerTransaction()) {
            try {
                Response asResponse = MessageUtils.createForwardedResponse(responseToPass, dialog,
                        getLastIncomingRequest(), context.getMessageFactory(), context.brokerContext
                                .getBrokerContactHeader(), logger);

                // cease all pending transactions (PRACK, INFO)
                if(responseToPass.getStatusCode() >= Response.MULTIPLE_CHOICES){
                    respondToPendingRequestsOnDialogTerminatingResponse();
                }

                ServerTransaction st = getLastServerTransaction();
                st.sendResponse(asResponse);
                st.setApplicationData(itsReferenceWrapper);

                logger.debug("Response sent towards {}", endpoint.getUriString());

                // null transaction in case of final response
                if (responseToPass.getStatusCode() >= Response.OK) {
                    setLastServerTransaction(null);
                    setLastIncomingRequest(null);
                }

            } catch (SipException | ParseException | InvalidArgumentException e) {
                logger.error("Not possible to send response to {}", endpoint.getUriString(), e);
                throw new SendResponseError("Not possible to send response back to: " + endpoint.getUriString(), e);
            }
        } else {
            // no server transaction -> final response already sent
            logger.debug("No server transaction for Request from {}", endpoint.getUriString());
        }
    }


    @Override
    public String toString() {
        return endpoint.toString();
    }

    @Override
    public boolean isImScf() {
        return false;
    }

    public String getAlias() {
        return endpoint.getAsAlias();
    }
}

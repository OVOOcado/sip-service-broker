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

import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.dispatcher.ServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.ASHandler;
import pl.ovoo.slee.resource.sip.broker.service.B2BDialogsHandler;
import pl.ovoo.slee.resource.sip.broker.service.ImScfHandler;
import pl.ovoo.slee.resource.sip.broker.service.OrchestratedSession;
import pl.ovoo.slee.resource.sip.broker.service.SessionManager;
import pl.ovoo.slee.resource.sip.broker.service.SipBrokerContext;
import pl.ovoo.slee.resource.sip.broker.service.config.Endpoint;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestratedService;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestrationRuleset;
import pl.ovoo.slee.resource.sip.broker.utils.SipBrokerLogger;

import javax.sip.Dialog;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Session context to be used during the whole SIP session.
 */
public class SessionContext {

    public final SipBrokerContext brokerContext;
    public final OrchestrationRuleset itsRuleset;
    public final OrchestratedSession itsSession;
    // dialog handler for IM-SCF A Leg (incoming)
    public ImScfHandler imScfHandlerA;
    // dialog handler for IM-SCF B Leg (outgoing)
    public ImScfHandler imScfHandlerB;
    private final Logger logger;
    // first ASHandler in the chain
    private ASHandler firstAsHandler;
    // this is the reference to the current ASHandler served
    private B2BDialogsHandler currentHandler;
    // INVITE request received from last AS (or ImScf)
    private Request lastIncomingInvite;
    // indicates who disconnected in order to pass BYE requests/responses in right direction
    private boolean byeInitiatedByCallingParty;
    // indicates that common reliable SessionProgress is being processed
    private boolean reliableResponseProcessing;
    // counts the active dialogs, to be used for session termination
    private Set<Dialog> pendingDialogs = new HashSet<>();

    public SessionContext(SipBrokerContext brokerContext, OrchestratedSession session, OrchestrationRuleset
            ruleset) {
        this.itsSession = session;
        this.brokerContext = brokerContext;
        this.itsRuleset = ruleset;
        logger = getSessionLogger(getClass());
        loadAsHandlers();
    }

    public void addDialog(Dialog d){
        pendingDialogs.add(d);
    }

    /**
     * Removes the dialog from the set
     *
     * @param dialog - dialog to remove
     *
     * @return true if dialog was present in the set
     */
    public boolean removeDialog(Dialog dialog){
        return pendingDialogs.remove(dialog);
    }

    public int getDialogsCount(){
        return pendingDialogs.size();
    }

    public B2BDialogsHandler getCurrentHandler(){
        return currentHandler;
    }

    public void setCurrentHandler(B2BDialogsHandler handler){
        currentHandler = handler;
    }


    /*
     * Creates ASHandlers with physical endpoints from configured services.
     */
    private void loadAsHandlers() {
        imScfHandlerA = new ImScfHandler(this, brokerContext.externalServiceProvider, "IM-SCF:A");
        imScfHandlerB = new ImScfHandler(this, brokerContext.externalServiceProvider, "IM-SCF:B");
        Iterator<OrchestratedService> it = itsRuleset.getServicesIterator();
        ASHandler previousAs = null;
        while (it.hasNext()) {
            OrchestratedService service = it.next();

            ServiceProvider handler = brokerContext.internalServiceProvider;
            if(service.isExternal()){
                logger.trace("Internal ServiceProvider assigned");
                // internal service handler
                handler = brokerContext.externalServiceProvider;
            }

            Endpoint endpoint = service.nextEndpoint();
            ASHandler asHandler = new ASHandler(endpoint, this, handler);

            if(firstAsHandler == null){
                firstAsHandler = asHandler;
            }

            // link AS handlers
            if(previousAs != null){
                previousAs.setNextHandler(asHandler);
                asHandler.setPreviousHandler(previousAs);
            } else {
                asHandler.setPreviousHandler(imScfHandlerA);
                imScfHandlerA.setNextHandler(asHandler);
            }
            previousAs = asHandler;

            logger.trace("Added {}", asHandler);
        }

        if(previousAs == null){
            throw new IllegalArgumentException("Invalid configuration for servicekey: " + itsRuleset.getServiceKey());
        }

        // link last AS back to IM-SCF
        ASHandler lastAs = previousAs;
        lastAs.setNextHandler(imScfHandlerB);
        imScfHandlerB.setPreviousHandler(lastAs);
    }

    /**
     * Rollbacks the AS handlers references.
     * This resets the state of the responding AS and all the ASs that are behind the responding one in the chain.
     * Applies to "stop" error logic handling.
     *
     * @param lastSuccessAs the AS prior to the one that responded with error
     */
    public void rollbackAsHandlers(ASHandler lastSuccessAs) {
        logger.trace("rollbackAsHandlers {}", lastSuccessAs);

        Iterator<OrchestratedService> it = itsRuleset.getServicesIterator();
        // first move iterator to initialAsName
        while (it.hasNext()){
            OrchestratedService service = it.next();
            if(service.getAlias().equals(lastSuccessAs.getAlias())){
                break;
            }
        }

        ASHandler previousAs = lastSuccessAs;
        while (it.hasNext()) {
            OrchestratedService service = it.next();

            ServiceProvider handler = brokerContext.internalServiceProvider;
            if(service.isExternal()){
                logger.trace("Internal ServiceProvider assigned");
                // internal service handler
                handler = brokerContext.externalServiceProvider;
            }

            Endpoint endpoint = service.nextEndpoint();
            ASHandler asHandler = new ASHandler(endpoint, this, handler);

            // link AS handlers
            previousAs.setNextHandler(asHandler);
            asHandler.setPreviousHandler(previousAs);
            previousAs = asHandler;

            logger.trace("Added {}", asHandler);
        }

        // in case rollback comes after B leg error
        imScfHandlerB = new ImScfHandler(this, brokerContext.externalServiceProvider, "IM-SCF:B");

        // link last AS back to IM-SCF
        ASHandler lastAs = previousAs;
        lastAs.setNextHandler(imScfHandlerB);
        imScfHandlerB.setPreviousHandler(lastAs);

    }

    /**
     * Returns SipBrokerLogger instance.
     * Use this within session context in order to keep particular session traceable.
     */
    public Logger getSessionLogger(Class clazz) {
        return new SipBrokerLogger(brokerContext.getTracer(clazz), itsSession.getID());
    }


    /**
     * Returns last INVITE received from AS (or IM-SCF)
     */
    public Request getLastIncomingInvite() {
        return lastIncomingInvite;
    }

    /**
     * Stores last INVITE received from AS (or IM-SCF)
     * @param lastIncomingInvite
     */
    public void setLastIncomingInvite(Request lastIncomingInvite) {
        this.lastIncomingInvite = lastIncomingInvite;
    }

    /**
     * It returns the first AS Handler (not IM-SCF!) in the chain.
     */
    public ASHandler getFirstChainedAs(){
        return firstAsHandler;
    }

    public MessageFactory getMessageFactory() {
        return brokerContext.messageFactory;
    }

    public HeaderFactory getHeaderFactory() {
        return brokerContext.headerFactory;
    }

    public SessionManager getSessionManager() {
        return brokerContext.getSessionManager();
    }

    public boolean isByeInitiatedByCallingParty() {
        return byeInitiatedByCallingParty;
    }

    public void setByeInitiatedByCallingParty(boolean byeInitiatedByCallingParty) {
        this.byeInitiatedByCallingParty = byeInitiatedByCallingParty;
    }

    public void setReliableResponseProcessing(boolean reliableResponseProcessing) {
        this.reliableResponseProcessing = reliableResponseProcessing;
    }

    public boolean isReliableResponseProcessing() {
        return reliableResponseProcessing;
    }
}

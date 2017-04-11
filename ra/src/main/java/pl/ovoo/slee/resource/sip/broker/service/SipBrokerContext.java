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
import pl.ovoo.slee.resource.sip.broker.SipBrokerUsageParameters;
import pl.ovoo.slee.resource.sip.broker.dispatcher.ExternalServiceProvider;
import pl.ovoo.slee.resource.sip.broker.dispatcher.InternalServiceProvider;
import pl.ovoo.slee.resource.sip.broker.dispatcher.ServiceProvider;
import pl.ovoo.slee.resource.sip.broker.service.config.BrokerConfiguration;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestrationConfig;
import pl.ovoo.slee.resource.sip.broker.utils.MessageUtils;
import pl.ovoo.slee.resource.sip.broker.utils.SipBrokerLogger;

import javax.sip.InvalidArgumentException;
import javax.sip.PeerUnavailableException;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.ContactHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RouteHeader;
import javax.sip.message.MessageFactory;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ResourceAdaptorContext;
import java.text.ParseException;

/**
 * This is the broker context, simplifies working with the provider, ra and broker utilities.
 */
public class SipBrokerContext {

    public final AddressFactory addressFactory;
    public final HeaderFactory headerFactory;
    public final MessageFactory messageFactory;
    public final String transport;
    public final ServiceProvider externalServiceProvider;
    public final ServiceProvider internalServiceProvider;
    public final int outgoingRetransmitTimer;

    private final ContactHeader brokerContactHeader;
    private final RouteHeader defaultImScfRouteHeader;
    private final ResourceAdaptorContext raContext;
    private SessionManager sessionManager;
    private OrchestrationConfig orchestrationConfig;

    /**
     *
     * @param raContext     - Resource Adaptor context
     * @param brokerConfig  - broker configuration parameters
     * @param sipFactory    - SIP Factory instance
     * @param sipProvider   - external SIP Stack Provider
     *
     * @throws PeerUnavailableException
     * @throws ParseException
     * @throws InvalidArgumentException
     */
    public SipBrokerContext(ResourceAdaptorContext raContext, BrokerConfiguration brokerConfig, SipFactory sipFactory,
                            SipProvider sipProvider) throws
            PeerUnavailableException, ParseException, InvalidArgumentException {
        this.raContext = raContext;
        addressFactory = sipFactory.createAddressFactory();
        headerFactory = sipFactory.createHeaderFactory();
        messageFactory = sipFactory.createMessageFactory();
        externalServiceProvider = new ExternalServiceProvider(sipProvider);
        internalServiceProvider = new InternalServiceProvider();
        transport = brokerConfig.getTransport();
        outgoingRetransmitTimer = brokerConfig.getRetransmitTimer();

        defaultImScfRouteHeader = MessageUtils.createImScfRouteHeader(headerFactory, addressFactory,
                brokerConfig.getImScfHost(), brokerConfig.getImScfPort());

        brokerContactHeader = MessageUtils.createBrokerContactHeader(headerFactory, addressFactory,
                brokerConfig.getBrokerHostname(), transport, sipProvider.getListeningPoint(transport).getPort());

    }

    /**
     * Updates broker orchestration config
     *
     * @param orchestrationConfig - the new orchestration config/rulesets to apply
     */
    public void updateConfig(OrchestrationConfig orchestrationConfig) {
        this.orchestrationConfig = orchestrationConfig;
    }

    /**
     * @return loaded orchestration config
     */
    public OrchestrationConfig getOrchestrationConfig() {
        return orchestrationConfig;
    }

    public String getTransport() {
        return transport;
    }

    /**
     * Returns Tracer instance named by classname.
     */
    public Tracer getTracer(Class clazz) {
        return raContext.getTracer(clazz.getSimpleName());
    }

    public Logger getLogger(Class clazz) {
        return new SipBrokerLogger(getTracer(clazz), null);
    }

    public ContactHeader getBrokerContactHeader() {
        return (ContactHeader) brokerContactHeader.clone();
    }

    public RouteHeader getDefaultImScfRouteHeader() {
        return (RouteHeader) defaultImScfRouteHeader.clone();
    }

    public SipBrokerUsageParameters getUsageParameters(){
        return (SipBrokerUsageParameters) raContext.getDefaultUsageParameterSet();
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }
}
